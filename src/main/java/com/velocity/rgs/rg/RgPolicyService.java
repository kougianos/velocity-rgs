package com.velocity.rgs.rg;

import com.velocity.rgs.common.error.ErrorCode;
import com.velocity.rgs.common.error.RgsException;
import com.velocity.rgs.common.money.Money;
import com.velocity.rgs.rg.domain.RgLimit;
import com.velocity.rgs.rg.domain.RgLimitType;
import com.velocity.rgs.rg.domain.RgStatus;
import com.velocity.rgs.rg.persistence.RgLimitRepository;
import com.velocity.rgs.wallet.persistence.WalletTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Responsible Gaming enforcement (§4.2).
 *
 * <p>{@link #validateStake} is called from inside each entry point's existing {@code @Transactional}
 * boundary, which is the shape {@code BonusBuyPolicyService.validate(...)} already has inside
 * {@code buyFeature()}. That is not a coincidence and not a new invention: a policy check that runs
 * outside the transaction that moves the money can be raced past, and the pattern was already proven
 * here on a different rule.
 *
 * <p><b>Only staked actions are gated.</b> A free-spin iteration, a Hold &amp; Spin respin, a blackjack
 * hit and a pick are all continuations of a round the player has already paid for, so blocking them
 * would strand a bought feature and take money for something never delivered. A limit stops the
 * <em>next</em> stake, never the round already in flight - which is also how a real RGS behaves, and is
 * the difference between protecting a player and penalising them.
 *
 * <p>Consumption is read from the wallet ledger rather than kept in a counter beside it; see
 * {@link WalletTransactionRepository#sumStakedSince}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RgPolicyService {

    private final RgPolicyProperties properties;
    private final RgLimitRepository limitRepository;
    private final WalletTransactionRepository transactionRepository;

    // ---------------------------------------------------------------- enforcement

    /**
     * Refuses a new stake that a limit or block forbids, and otherwise records the activity that the
     * session clock and reality check are measured from.
     *
     * <p>Evaluated <em>before</em> the stake is taken and against the consumption to date, so a limit
     * stops the bet that would breach it rather than reporting the breach afterwards. The one exception
     * is the session-duration limit, which is about time rather than money and can only be checked
     * against the clock.
     *
     * @param stake what the player is about to risk, used to decide whether this bet would cross a
     *              limit rather than merely approach it
     */
    @Transactional
    public void validateStake(String playerId, String currency, BigDecimal stake) {
        if (!properties.isEnabled()) {
            return;
        }
        Instant now = Instant.now();
        RgLimit limit = limitRepository.findById(playerId)
                .orElseGet(() -> seedDefaults(playerId, now));

        if (limit.isSelfExcluded()) {
            throw selfExcluded(limit);
        }
        if (limit.isCoolingOff(now)) {
            throw limitExceeded(RgLimitType.COOL_OFF, null, null, limit.getCoolOffUntil(), currency,
                    "You asked for a break from play");
        }

        rollSessionIfIdle(limit, now);

        if (limit.getSessionLimitMinutes() != null && limit.getSessionStartedAt() != null) {
            long used = Duration.between(limit.getSessionStartedAt(), now).toMinutes();
            if (used >= limit.getSessionLimitMinutes()) {
                throw limitExceeded(RgLimitType.SESSION_DURATION,
                        BigDecimal.valueOf(used), BigDecimal.valueOf(limit.getSessionLimitMinutes()),
                        null, currency,
                        "You have played for " + used + " of your " + limit.getSessionLimitMinutes()
                                + " minute session limit");
            }
        }

        BigDecimal safeStake = stake == null ? BigDecimal.ZERO : stake;
        if (limit.getWagerLimit() != null) {
            BigDecimal wagered = stakedSince(playerId, limit.getPeriodStartedAt());
            if (wagered.add(safeStake).compareTo(limit.getWagerLimit()) > 0) {
                throw limitExceeded(RgLimitType.WAGER, wagered, limit.getWagerLimit(), null, currency,
                        "This stake would take you past your "
                                + scaled(limit.getWagerLimit(), currency) + " " + currency
                                + " wager limit");
            }
        }
        if (limit.getLossLimit() != null) {
            BigDecimal netLoss = netLossSince(playerId, limit.getPeriodStartedAt());
            // The stake counts toward the loss because, at the moment of betting, it is lost - anything
            // it returns is credited separately and lifts the figure back up. Checking the pre-bet net
            // loss alone would let a single stake of any size through the instant before the limit.
            if (netLoss.add(safeStake).compareTo(limit.getLossLimit()) > 0) {
                throw limitExceeded(RgLimitType.LOSS, netLoss, limit.getLossLimit(), null, currency,
                        "This stake would take you past your "
                                + scaled(limit.getLossLimit(), currency) + " " + currency
                                + " loss limit");
            }
        }

        limit.setLastActivityAt(now);
        limit.setUpdatedAt(now);
        limitRepository.save(limit);
    }

    /**
     * Whether a further stake would be accepted, without throwing - so a response can withhold
     * {@code SPIN} from {@code availableActions} the moment a limit is reached.
     *
     * <p>This is what makes the button die server-side. The client is not deciding anything: it is
     * rendering an action list that no longer contains the action, and a client that ignored it would
     * be refused by {@link #validateStake} anyway.
     */
    @Transactional(readOnly = true)
    public boolean canStake(String playerId, BigDecimal nextStake) {
        if (!properties.isEnabled()) {
            return true;
        }
        try {
            checkOnly(playerId, nextStake, Instant.now());
            return true;
        } catch (RgsException ex) {
            return false;
        }
    }

    // ---------------------------------------------------------------- status

    /**
     * The player's own view of their limits and how much of each they have used.
     *
     * <p>Seeds the ruleset defaults for a player who has none, which is why this writes rather than
     * reading. Opening the panel should show the numbers that will actually be enforced on the next
     * spin, not an empty form that quietly fills itself in the moment play starts - a limit the player
     * was never shown is a limit they cannot be said to have accepted.
     */
    @Transactional
    public RgStatus status(String playerId, String currency) {
        Instant now = Instant.now();
        RgLimit limit = limitRepository.findById(playerId).orElseGet(() -> seedDefaults(playerId, now));

        BigDecimal wagered = stakedSince(playerId, limit.getPeriodStartedAt());
        BigDecimal netLoss = netLossSince(playerId, limit.getPeriodStartedAt());
        long sessionMinutes = limit.getSessionStartedAt() == null || isIdle(limit, now)
                ? 0
                : Duration.between(limit.getSessionStartedAt(), now).toMinutes();

        RgLimitType blockedBy = null;
        Instant blockedUntil = null;
        if (properties.isEnabled()) {
            if (limit.isCoolingOff(now)) {
                blockedBy = RgLimitType.COOL_OFF;
                blockedUntil = limit.getCoolOffUntil();
            } else if (limit.getSessionLimitMinutes() != null
                    && sessionMinutes >= limit.getSessionLimitMinutes()) {
                blockedBy = RgLimitType.SESSION_DURATION;
            } else if (limit.getWagerLimit() != null
                    && wagered.compareTo(limit.getWagerLimit()) >= 0) {
                blockedBy = RgLimitType.WAGER;
            } else if (limit.getLossLimit() != null
                    && netLoss.compareTo(limit.getLossLimit()) >= 0) {
                blockedBy = RgLimitType.LOSS;
            }
        }

        boolean selfExcluded = properties.isEnabled() && limit.isSelfExcluded();
        return new RgStatus(
                properties.isEnabled(),
                !selfExcluded && blockedBy == null,
                blockedBy,
                blockedUntil,
                selfExcluded,
                limit.getSessionLimitMinutes(),
                sessionMinutes,
                limit.getLossLimit(),
                netLoss,
                limit.getWagerLimit(),
                wagered,
                limit.getRealityCheckMinutes(),
                realityCheckDue(limit, now),
                limit.getPeriodStartedAt(),
                currency);
    }

    // ---------------------------------------------------------------- player actions

    /**
     * Sets the player's limits. Null leaves a limit unchanged; the bounds in the ruleset cap how loose
     * one may be made, never how strict - refusing to let someone protect themselves further would
     * invert the point of the panel.
     */
    @Transactional
    public RgStatus setLimits(String playerId, String currency, Integer sessionLimitMinutes,
                              BigDecimal lossLimit, BigDecimal wagerLimit, Integer realityCheckMinutes) {
        Instant now = Instant.now();
        RgLimit limit = limitRepository.findById(playerId).orElseGet(() -> RgLimit.fresh(playerId, now));
        if (limit.isSelfExcluded()) {
            throw selfExcluded(limit);
        }

        RgPolicyProperties.Bounds bounds = properties.getBounds();
        if (sessionLimitMinutes != null) {
            limit.setSessionLimitMinutes(
                    requireWithin("sessionLimitMinutes", sessionLimitMinutes, 1,
                            bounds.getMaxSessionLimitMinutes()));
        }
        if (realityCheckMinutes != null) {
            limit.setRealityCheckMinutes(
                    requireWithin("realityCheckMinutes", realityCheckMinutes, 1,
                            bounds.getMaxRealityCheckMinutes()));
        }
        if (lossLimit != null) {
            limit.setLossLimit(requireWithin("lossLimit", lossLimit, bounds.getMaxLossLimit()));
        }
        if (wagerLimit != null) {
            limit.setWagerLimit(requireWithin("wagerLimit", wagerLimit, bounds.getMaxWagerLimit()));
        }
        limit.setUpdatedAt(now);
        limitRepository.save(limit);
        log.info("RG limits updated player={} session={}min loss={} wager={} realityCheck={}min",
                playerId, limit.getSessionLimitMinutes(), limit.getLossLimit(), limit.getWagerLimit(),
                limit.getRealityCheckMinutes());
        return status(playerId, currency);
    }

    /** Starts a timed break. Extending an existing cool-off is allowed; shortening one is not. */
    @Transactional
    public RgStatus coolOff(String playerId, String currency, int hours) {
        Instant now = Instant.now();
        requireWithin("hours", hours, 1, properties.getBounds().getMaxCoolOffHours());
        RgLimit limit = limitRepository.findById(playerId).orElseGet(() -> RgLimit.fresh(playerId, now));
        if (limit.isSelfExcluded()) {
            throw selfExcluded(limit);
        }

        Instant until = now.plus(Duration.ofHours(hours));
        // A player mid-break who asks for a shorter one is asking to come back sooner, which is the one
        // direction a break must not move.
        if (limit.getCoolOffUntil() == null || until.isAfter(limit.getCoolOffUntil())) {
            limit.setCoolOffUntil(until);
        }
        limit.setUpdatedAt(now);
        limitRepository.save(limit);
        log.info("RG cool-off started player={} until={}", playerId, limit.getCoolOffUntil());
        return status(playerId, currency);
    }

    /** Self-exclusion. One way, by design - there is no un-exclude on this service. */
    @Transactional
    public RgStatus selfExclude(String playerId, String currency) {
        Instant now = Instant.now();
        RgLimit limit = limitRepository.findById(playerId).orElseGet(() -> RgLimit.fresh(playerId, now));
        if (!limit.isSelfExcluded()) {
            limit.setSelfExcludedAt(now);
            limit.setUpdatedAt(now);
            limitRepository.save(limit);
            log.info("RG self-exclusion recorded player={}", playerId);
        }
        return status(playerId, currency);
    }

    /** Marks the reality check as seen, restarting the interval. */
    @Transactional
    public RgStatus acknowledgeRealityCheck(String playerId, String currency) {
        Instant now = Instant.now();
        RgLimit limit = limitRepository.findById(playerId).orElseGet(() -> RgLimit.fresh(playerId, now));
        limit.setLastRealityCheckAt(now);
        limit.setUpdatedAt(now);
        limitRepository.save(limit);
        return status(playerId, currency);
    }

    /**
     * Clears every limit and block for one player.
     *
     * <p>Exists only so the demo can be replayed - self-exclusion is otherwise final, and a portfolio
     * account that bricks itself the first time someone clicks the scariest button in the panel is a
     * feature nobody gets to see twice. Reachable only from the demo-gated dev controller, never from
     * the player-facing API.
     */
    @Transactional
    public void resetForDemo(String playerId) {
        limitRepository.deleteById(playerId);
        log.info("RG state reset for demo player={}", playerId);
    }

    // ---------------------------------------------------------------- internals

    private void checkOnly(String playerId, BigDecimal nextStake, Instant now) {
        RgLimit limit = limitRepository.findById(playerId).orElse(null);
        if (limit == null) {
            return;
        }
        if (limit.isSelfExcluded()) {
            throw selfExcluded(limit);
        }
        if (limit.isCoolingOff(now)) {
            throw limitExceeded(RgLimitType.COOL_OFF, null, null, limit.getCoolOffUntil(), null, "");
        }
        if (limit.getSessionLimitMinutes() != null && limit.getSessionStartedAt() != null
                && !isIdle(limit, now)
                && Duration.between(limit.getSessionStartedAt(), now).toMinutes()
                    >= limit.getSessionLimitMinutes()) {
            throw limitExceeded(RgLimitType.SESSION_DURATION, null, null, null, null, "");
        }
        BigDecimal stake = nextStake == null ? BigDecimal.ZERO : nextStake;
        if (limit.getWagerLimit() != null
                && stakedSince(playerId, limit.getPeriodStartedAt()).add(stake)
                    .compareTo(limit.getWagerLimit()) > 0) {
            throw limitExceeded(RgLimitType.WAGER, null, null, null, null, "");
        }
        if (limit.getLossLimit() != null
                && netLossSince(playerId, limit.getPeriodStartedAt()).add(stake)
                    .compareTo(limit.getLossLimit()) > 0) {
            throw limitExceeded(RgLimitType.LOSS, null, null, null, null, "");
        }
    }

    /**
     * A player with no row yet gets the ruleset's demo defaults, written down as theirs.
     *
     * <p>Materialised rather than applied implicitly so the panel can show real numbers on a first
     * visit, and so a player who later loosens one has changed something concrete rather than diverged
     * from a default that keeps moving underneath them.
     *
     * <p>Deliberately does not start the session clock. Seeding happens on first contact with RG, which
     * includes merely opening the panel, and reading your own limits is not playing - starting the
     * clock here would spend a player's session limit while they sat on a settings page.
     * {@code rollSessionIfIdle} starts it on the first actual stake.
     */
    private RgLimit seedDefaults(String playerId, Instant now) {
        RgPolicyProperties.Defaults defaults = properties.getDefaults();
        RgLimit limit = RgLimit.fresh(playerId, now);
        limit.setSessionLimitMinutes(defaults.getSessionLimitMinutes());
        limit.setLossLimit(defaults.getLossLimit());
        limit.setWagerLimit(defaults.getWagerLimit());
        limit.setRealityCheckMinutes(defaults.getRealityCheckMinutes());
        return limitRepository.save(limit);
    }

    /** A long enough gap between stakes is a new sitting, so the session clock restarts. */
    private void rollSessionIfIdle(RgLimit limit, Instant now) {
        if (limit.getSessionStartedAt() == null || isIdle(limit, now)) {
            limit.setSessionStartedAt(now);
            limit.setLastRealityCheckAt(now);
        }
    }

    private boolean isIdle(RgLimit limit, Instant now) {
        Instant last = limit.getLastActivityAt();
        return last == null
                || Duration.between(last, now).toMinutes() >= properties.getSessionIdleResetMinutes();
    }

    private boolean realityCheckDue(RgLimit limit, Instant now) {
        if (!properties.isEnabled() || limit.getRealityCheckMinutes() == null
                || limit.getSessionStartedAt() == null || isIdle(limit, now)) {
            return false;
        }
        Instant since = limit.getLastRealityCheckAt() == null
                ? limit.getSessionStartedAt()
                : limit.getLastRealityCheckAt();
        return Duration.between(since, now).toMinutes() >= limit.getRealityCheckMinutes();
    }

    private BigDecimal stakedSince(String playerId, Instant since) {
        BigDecimal staked = transactionRepository.sumStakedSince(playerId, since);
        return staked == null ? BigDecimal.ZERO : staked;
    }

    /** Net loss cannot go below zero: a player up on the period has lost nothing, not a negative amount. */
    private BigDecimal netLossSince(String playerId, Instant since) {
        BigDecimal won = transactionRepository.sumWonSince(playerId, since);
        BigDecimal net = stakedSince(playerId, since).subtract(won == null ? BigDecimal.ZERO : won);
        return net.signum() < 0 ? BigDecimal.ZERO : net;
    }

    private RgsException selfExcluded(RgLimit limit) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("selfExcludedAt", limit.getSelfExcludedAt().toString());
        return new RgsException(ErrorCode.RG_SELF_EXCLUDED,
                "This account is self-excluded and cannot be used to play", null, context, null);
    }

    private RgsException limitExceeded(RgLimitType type, BigDecimal used, BigDecimal limit,
                                       Instant resetsAt, String currency, String message) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("limit", type.name());
        // Money is reported at the currency's own scale. The column is NUMERIC(19,4), so a limit set as
        // 1.00 reads back as 1.0000 and would reach the player's screen with two spurious decimals.
        boolean money = type == RgLimitType.LOSS || type == RgLimitType.WAGER;
        if (used != null) {
            context.put("used", money ? scaled(used, currency) : used.toPlainString());
        }
        if (limit != null) {
            context.put("limitValue", money ? scaled(limit, currency) : limit.toPlainString());
        }
        if (resetsAt != null) {
            context.put("resetsAt", resetsAt.toString());
        }
        if (currency != null) {
            context.put("currency", currency);
        }
        return new RgsException(ErrorCode.RG_LIMIT_EXCEEDED,
                message.isBlank() ? "Play is blocked by your " + type.name() + " limit" : message,
                null, context, null);
    }

    private static String scaled(BigDecimal amount, String currency) {
        return currency == null
                ? amount.toPlainString()
                : amount.setScale(Money.minorUnitScale(currency), RoundingMode.HALF_UP).toPlainString();
    }

    private static int requireWithin(String field, int value, int min, int max) {
        if (value < min || value > max) {
            throw new RgsException(ErrorCode.VALIDATION_ERROR,
                    field + " must be between " + min + " and " + max + ", found " + value);
        }
        return value;
    }

    private static BigDecimal requireWithin(String field, BigDecimal value, BigDecimal max) {
        if (value.signum() < 0 || value.compareTo(max) > 0) {
            throw new RgsException(ErrorCode.VALIDATION_ERROR,
                    field + " must be between 0 and " + max + ", found " + value);
        }
        return value;
    }
}
