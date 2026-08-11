package com.velocity.rgs.jackpot;

import com.velocity.rgs.common.money.Money;
import com.velocity.rgs.jackpot.domain.JackpotAward;
import com.velocity.rgs.jackpot.domain.JackpotContribution;
import com.velocity.rgs.jackpot.domain.JackpotPool;
import com.velocity.rgs.jackpot.domain.JackpotPoolView;
import com.velocity.rgs.jackpot.domain.JackpotTier;
import com.velocity.rgs.jackpot.domain.JackpotPoolId;
import com.velocity.rgs.jackpot.domain.JackpotWin;
import com.velocity.rgs.jackpot.persistence.JackpotPoolRepository;
import com.velocity.rgs.jackpot.persistence.JackpotWinRepository;
import com.velocity.rgs.rng.RandomNumberGenerator;
import com.velocity.rgs.slot.math.config.ProgressiveJackpotConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Reads and grows the progressive pools (§2).
 *
 * <p><b>Postgres is the pool of record; Redis caches only the rendered view.</b> Every path that moves
 * money - contribute, award - reads and writes Postgres directly. The single cached read is
 * {@link #pools}, which serves the public lobby ticker: a page every visitor polls, against the four
 * most contended rows in the schema.
 *
 * <p>"Why not just keep the pool in Redis" has one answer. A pool is money owed to a player who has not
 * won it yet, and Redis is allowed to evict. A cached <em>picture</em> of the pools may be a second
 * stale; the pools themselves may not be anything but exact.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JackpotService {

    /** The pool column is {@code NUMERIC(19,4)}, and contributions genuinely need those extra places:
     *  1% of a 0.20 stake is 0.002, which rounds to nothing at a currency's two. */
    private static final int POOL_SCALE = 4;

    private final JackpotPoolRepository poolRepository;
    private final JackpotWinRepository winRepository;
    private final JackpotProperties properties;
    private final JackpotPoolCache poolCache;

    /** Player -> tier armed by the demo control, consumed by that player's next contributing spin. */
    private final Map<String, JackpotTier> forcedWins = new ConcurrentHashMap<>();

    // ---------------------------------------------------------------- read

    /**
     * Every tier in one currency, smallest first.
     *
     * <p>Sorted by the enum's own order rather than by amount. A jackpot ladder is a fixed ladder, and
     * ordering by value would let the strip reorder itself the moment a lower tier was contributed past
     * a higher one that had just been won - a display that shuffles under a player who is looking at it.
     */
    @Transactional(readOnly = true)
    public List<JackpotPoolView> pools(String currency) {
        // Read-through cache, and only here. Every other caller in this class reads Postgres directly:
        // the cache exists for the public ticker, which is polled by every visitor, and nothing that
        // moves money is allowed to make a decision from it.
        List<JackpotPoolView> cached = poolCache.get(currency);
        if (cached != null) {
            return cached;
        }
        List<JackpotPoolView> fresh = readPools(currency);
        poolCache.put(currency, fresh);
        return fresh;
    }

    // ---------------------------------------------------------------- contribute

    /**
     * Feeds one stake into the pools, and answers with what it fed and where the pools now stand.
     *
     * <p><b>Called from inside the spin's existing {@code @Transactional} boundary</b>, which is the
     * whole design and not an implementation detail. The contribution and the bet debit commit together
     * or not at all: a spin that fails after the debit cannot leave a pool holding money nobody paid,
     * and a spin that succeeds cannot fail to feed the pool it advertised. It is the same shape
     * {@code RgPolicyService.validateStake} already has for the same reason - a rule that runs outside
     * the transaction moving the money can be raced past.
     *
     * <p><b>The player is not charged for this.</b> The debit is exactly the stake, the game pays
     * exactly its calibrated return, and the contribution is modelled as coming out of the house
     * margin. A real operator carves it out of the theoretical return instead, which would mean
     * recalibrating all six games to a lower base RTP; that is a maths exercise, not an architecture
     * one, and doing it here would break every RTP guard to demonstrate nothing extra. The seam is
     * unaffected either way: the pool grows by rate x stake whichever pocket funds it.
     *
     * @param game the spinning game's block. A game that does not configure one contributes nothing,
     *             which is also exactly when the lobby shows it no jackpot card
     */
    @Transactional
    public JackpotContribution contribute(ProgressiveJackpotConfig game, String currency,
                                          BigDecimal stake) {
        if (!properties.isEnabled() || game == null || !game.enabled()
                || stake == null || stake.signum() <= 0) {
            return JackpotContribution.none(currency);
        }

        BigDecimal rate = game.rateOr(properties.getDefaultContributionRate());
        BigDecimal total = stake.multiply(rate);
        if (total.signum() <= 0) {
            return JackpotContribution.none(currency);
        }

        Instant now = Instant.now();
        BigDecimal contributed = BigDecimal.ZERO;
        for (JackpotTier tier : JackpotTier.values()) {
            BigDecimal delta = total.multiply(properties.tier(tier).getShare())
                    .setScale(POOL_SCALE, RoundingMode.DOWN);
            if (delta.signum() <= 0) {
                continue;
            }
            int updated = poolRepository.addToPool(tier, currency, delta, now);
            if (updated == 0) {
                // No pool for this tier in this currency. Not an error worth failing a spin over - the
                // player's bet is fine and the game is playable - but it does mean a configured
                // contribution went nowhere, so it is a warning rather than a debug line.
                log.warn("No {} pool in {} to contribute to; {} not credited", tier, currency, delta);
                continue;
            }
            contributed = contributed.add(delta);
        }

        // Reported as the sum of what actually landed, not as rate x stake. Rounding each tier down
        // means the two can differ, and the number shown to a player has to be the number the pools
        // actually received.
        return new JackpotContribution(contributed, currency, readPools(currency));
    }

    // ---------------------------------------------------------------- award

    /**
     * Decides whether this spin lands a jackpot, and which tier.
     *
     * <p>Drawn from the <em>spin's</em> RNG, so the roll is captured in the round's draw log and a
     * replay reaches the same answer. A jackpot decided by a side channel would be the one outcome in
     * the game that could not be proven after the fact, which on the largest prize is exactly backwards.
     *
     * <p>Largest tier first, and at most one per spin: a spin that hits the Mega does not also collect
     * the Mini on the way past.
     */
    public Optional<JackpotTier> rollAward(ProgressiveJackpotConfig game, String playerId,
                                           RandomNumberGenerator rng) {
        if (!properties.isEnabled() || game == null || !game.enabled()) {
            return Optional.empty();
        }
        // A forced win is consumed whether or not the dice would have agreed, and consumed exactly
        // once. Demo-mode only; see JackpotDevController for why it exists at all.
        JackpotTier forced = forcedWins.remove(playerId);
        if (forced != null) {
            log.info("Forced {} jackpot for player={} (demo control)", forced, playerId);
            return Optional.of(forced);
        }
        for (int i = JackpotTier.values().length - 1; i >= 0; i--) {
            JackpotTier tier = JackpotTier.values()[i];
            if (rng.nextIndex(properties.tier(tier).getAwardOneInN()) == 0) {
                return Optional.of(tier);
            }
        }
        return Optional.empty();
    }

    /**
     * Pays a pool out, once.
     *
     * <p>Runs inside the spin's transaction alongside the wallet credit, so the pool reset, the audit
     * row and the money all commit together. Three independent things stop a double award, and the
     * layering is deliberate - see {@code V15__jackpot_win.sql}:
     * <ol>
     *   <li>A retried spin never gets here: the Idempotency-Key layer replays the stored response
     *       without re-running the spin body.</li>
     *   <li>The pool reset is a compare-and-swap on the row version, so two spins racing for the same
     *       tier cannot both win it. The loser returns empty and pays nothing.</li>
     *   <li>{@code jackpot_win} is unique on {@code round_id}, so if the first two ever failed the
     *       insert would fail and take the transaction down rather than paying twice.</li>
     * </ol>
     *
     * @return the award, or empty if this spin lost the race for the pool
     */
    @Transactional
    public Optional<JackpotAward> award(JackpotTier tier, String currency, String roundId,
                                        String playerId, String sessionId, String gameId) {
        JackpotPool pool = poolRepository.findById(new JackpotPoolId(tier, currency)).orElse(null);
        if (pool == null) {
            log.warn("No {} pool in {} to award", tier, currency);
            return Optional.empty();
        }

        BigDecimal held = pool.getAmount();
        BigDecimal seed = pool.getSeedAmount();
        Instant now = Instant.now();

        // A wallet holds two decimal places and the pool holds four. The player is paid what can
        // actually be credited, rounded DOWN, and the sub-cent remainder is carried into the next
        // cycle on top of the seed. Rounding it away instead would delete money players contributed -
        // a fraction of a cent per award, forever, which is exactly the kind of leak that is invisible
        // until an auditor sums the ledger.
        BigDecimal won = held.setScale(Money.minorUnitScale(currency), RoundingMode.DOWN);
        BigDecimal carried = held.subtract(won);
        BigDecimal resetTo = seed.add(carried);

        if (won.signum() <= 0) {
            log.warn("{} {} pool holds {} which is below one minor unit; not awarding",
                    currency, tier, held.toPlainString());
            return Optional.empty();
        }

        if (poolRepository.resetIfUnchanged(tier, currency, resetTo, pool.getVersion(), now) == 0) {
            // Another spin took this pool between our read and our write. Not an error: the player
            // simply did not win it, and the alternative - paying out a figure that no longer exists -
            // is the bug this check is here to prevent.
            log.info("Lost the race for the {} {} pool on round={}", currency, tier, roundId);
            return Optional.empty();
        }

        String txId = roundId + ":jackpot";
        JackpotWin win = winRepository.save(JackpotWin.builder()
                .winId(UUID.randomUUID().toString())
                .roundId(roundId)
                .playerId(playerId)
                .sessionId(sessionId)
                .gameId(gameId)
                .tier(tier)
                .currency(currency)
                .amount(won)
                .seedAmount(seed)
                .txId(txId)
                .wonAt(now)
                .build());

        // The pool just dropped by its whole value. A ticker showing the old figure for even a second
        // after that is advertising a prize somebody has already been paid.
        poolCache.evict(currency);

        log.info("JACKPOT {} won player={} round={} paid={} {} - pool reset to {} (carried {})",
                tier, playerId, roundId, won.toPlainString(), currency,
                resetTo.toPlainString(), carried.toPlainString());

        return Optional.of(new JackpotAward(tier, tier.label(), currency,
                won, scaled(resetTo, currency), win.getWinId(), txId));
    }

    /**
     * Arms the next spin of one player to win a tier (demo mode only).
     *
     * <p>Kept in memory rather than persisted: it is a presentation aid that should not survive a
     * restart, and a forced jackpot that outlived the demo it was armed for would be a genuinely bad
     * surprise. Everything downstream of the roll is the real path - the CAS, the audit row, the
     * credit - so what a viewer watches is the production mechanism, only the dice are rigged.
     */
    public void forceNextWin(String playerId, JackpotTier tier) {
        forcedWins.put(playerId, tier);
    }

    /**
     * Re-reads the pools onto an existing contribution.
     *
     * <p>Needed because a contribution snapshots the pools when it lands, and an award happens after
     * it - so a spin that won would otherwise report the pool at its pre-win figure and the client
     * would render a jackpot celebration next to a pool that had visibly not reset. The contributed
     * amount is unchanged; only the standings are refreshed.
     */
    @Transactional(readOnly = true)
    public JackpotContribution withCurrentPools(JackpotContribution contribution) {
        return new JackpotContribution(contribution.amount(), contribution.currency(),
                readPools(contribution.currency()));
    }

    // ---------------------------------------------------------------- internals

    private List<JackpotPoolView> readPools(String currency) {
        return poolRepository.findByIdCurrency(currency).stream()
                .sorted(Comparator.comparing(JackpotPool::tier))
                .map(pool -> toView(pool, currency))
                .toList();
    }

    private JackpotPoolView toView(JackpotPool pool, String currency) {
        JackpotTier tier = pool.tier();
        BigDecimal amount = scaled(pool.getAmount(), currency);
        BigDecimal seed = scaled(pool.getSeedAmount(), currency);
        BigDecimal grown = scaled(pool.grownBy(), currency);

        return winRepository.findFirstByTierAndCurrencyOrderByWonAtDesc(tier, currency)
                .map(win -> new JackpotPoolView(tier, tier.label(), currency, amount, seed, grown,
                        JackpotPoolView.maskPlayer(win.getPlayerId()),
                        win.getWonAt(),
                        scaled(win.getAmount(), currency)))
                .orElseGet(() -> JackpotPoolView.neverWon(tier, tier.label(), currency,
                        amount, seed, grown));
    }

    /**
     * Money leaves at its own currency's scale. The column is NUMERIC(19,4), so a 10.00 pool reads back
     * as 10.0000 and would reach the client with two decimals nobody asked for.
     *
     * <p>Rounded {@code DOWN}, not half-up: a pool that has taken 0.0080 in contributions has not yet
     * reached its next cent, and showing it as though it had would advertise a prize larger than the
     * one the winner would actually be paid.
     */
    private static BigDecimal scaled(BigDecimal amount, String currency) {
        return amount.setScale(Money.minorUnitScale(currency), RoundingMode.DOWN);
    }
}
