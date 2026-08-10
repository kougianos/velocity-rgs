package com.velocity.rgs.jackpot;

import com.velocity.rgs.jackpot.domain.JackpotTier;
import com.velocity.rgs.rng.RandomNumberGenerator;
import com.velocity.rgs.slot.math.config.ProgressiveJackpotConfig;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The award roll, on its own (§2).
 *
 * <p>A plain unit test with a scripted RNG, and it exists for a specific reason: the integration suite
 * runs with the award odds made unreachable so a stray jackpot cannot perturb a test measuring
 * something else, and every award test there forces its win. That leaves the roll itself - the part
 * that decides whether a real player wins - covered nowhere. This is that cover.
 */
class JackpotAwardRollTest {

    private static final ProgressiveJackpotConfig CONTRIBUTING =
            new ProgressiveJackpotConfig(true, null);

    /** Draws the values it is given, in order, ignoring the bound. */
    private static RandomNumberGenerator scripted(int... draws) {
        List<Integer> queue = new ArrayList<>();
        for (int d : draws) {
            queue.add(d);
        }
        return bound -> queue.isEmpty() ? 1 : queue.remove(0);
    }

    private static JackpotService serviceWith(Map<JackpotTier, Integer> odds) {
        JackpotProperties props = new JackpotProperties();
        Map<JackpotTier, JackpotProperties.Tier> tiers = new EnumMap<>(JackpotTier.class);
        BigDecimal[] shares = {
                new BigDecimal("0.40"), new BigDecimal("0.30"),
                new BigDecimal("0.20"), new BigDecimal("0.10")
        };
        int i = 0;
        for (JackpotTier tier : JackpotTier.values()) {
            JackpotProperties.Tier t = new JackpotProperties.Tier();
            t.setSeed(new BigDecimal("10.00"));
            t.setShare(shares[i++]);
            t.setAwardOneInN(odds.get(tier));
            tiers.put(tier, t);
        }
        props.setTiers(tiers);
        return new JackpotService(null, null, props);
    }

    private static Map<JackpotTier, Integer> allOdds(int n) {
        Map<JackpotTier, Integer> odds = new EnumMap<>(JackpotTier.class);
        for (JackpotTier tier : JackpotTier.values()) {
            odds.put(tier, n);
        }
        return odds;
    }

    /** A draw of 0 is a hit. Rolled largest tier first, so a Mega hit wins the Mega. */
    @Test
    void aZeroDrawOnTheTopTierWinsTheMega() {
        JackpotService service = serviceWith(allOdds(10));

        assertThat(service.rollAward(CONTRIBUTING, "p-1", scripted(0)))
                .contains(JackpotTier.MEGA);
    }

    /**
     * At most one tier per spin: a spin that misses the top tiers and hits the Mini wins only the Mini.
     * The draws are consumed in Mega, Major, Minor, Mini order.
     */
    @Test
    void theFirstTierToHitWinsAndNoOther() {
        JackpotService service = serviceWith(allOdds(10));

        assertThat(service.rollAward(CONTRIBUTING, "p-1", scripted(1, 1, 1, 0)))
                .contains(JackpotTier.MINI);
    }

    /** Missing every tier is the ordinary case, and it awards nothing. */
    @Test
    void missingEveryTierAwardsNothing() {
        JackpotService service = serviceWith(allOdds(10));

        assertThat(service.rollAward(CONTRIBUTING, "p-1", scripted(1, 1, 1, 1)))
                .isEmpty();
    }

    /** A game that does not feed the pools cannot win them, whatever the dice say. */
    @Test
    void aNonContributingGameNeverWins() {
        JackpotService service = serviceWith(allOdds(1));

        assertThat(service.rollAward(ProgressiveJackpotConfig.disabled(), "p-1", scripted(0, 0, 0, 0)))
                .isEmpty();
    }

    /**
     * A forced win overrides the dice and is consumed exactly once.
     *
     * <p>The second roll falls through to the RNG, which here misses everything - so an armed demo
     * cannot quietly keep paying out on every subsequent spin.
     */
    @Test
    void aForcedWinAppliesOnceAndThenClears() {
        JackpotService service = serviceWith(allOdds(1_000_000));
        service.forceNextWin("p-1", JackpotTier.MAJOR);

        assertThat(service.rollAward(CONTRIBUTING, "p-1", scripted(1, 1, 1, 1)))
                .contains(JackpotTier.MAJOR);
        assertThat(service.rollAward(CONTRIBUTING, "p-1", scripted(1, 1, 1, 1)))
                .as("armed for one spin, not for the session")
                .isEmpty();
    }

    /** Arming one player does not arm another. */
    @Test
    void aForcedWinIsScopedToItsPlayer() {
        JackpotService service = serviceWith(allOdds(1_000_000));
        service.forceNextWin("p-armed", JackpotTier.MINI);

        Optional<JackpotTier> other = service.rollAward(CONTRIBUTING, "p-other", scripted(1, 1, 1, 1));

        assertThat(other).isEmpty();
        assertThat(service.rollAward(CONTRIBUTING, "p-armed", scripted(1, 1, 1, 1)))
                .contains(JackpotTier.MINI);
    }
}
