package com.velocity.rgs.jackpot;

import com.velocity.rgs.jackpot.domain.JackpotTier;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The progressive jackpot ruleset (§2), from {@code rgs.jackpot.*}.
 *
 * <p>Two halves that belong at different levels, and keeping them apart is the point:
 * <ul>
 *   <li><b>Here</b> - what a tier <em>is</em>: its seed, its share of each contribution, the currencies
 *       it runs in. A pool is shared across every game, so no single game may own these numbers.</li>
 *   <li><b>In the game's math config</b> - whether that game feeds the pools at all, and at what rate.
 *       That belongs beside the game because it is a property of the game's economics, and because the
 *       lobby derives its feature cards from the math block. A game advertises a jackpot exactly when
 *       it contributes to one.</li>
 * </ul>
 *
 * <p>The default rate is a platform-wide fallback for games that opt in without naming one, so turning
 * the whole portfolio's contribution up or down is one line rather than six.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "rgs.jackpot")
public class JackpotProperties {

    /** Master switch. Off means no spin contributes and no pool moves, for a clean demo reset. */
    private boolean enabled = true;

    /**
     * Fraction of the stake a contributing game feeds to the pools when it names no rate of its own.
     *
     * <p>Not deducted from the player and not taken out of the game's return: see
     * {@code JackpotService.contribute}, which explains why a demo models this as house-funded rather
     * than recalibrating six games' RTP.
     */
    private BigDecimal defaultContributionRate = new BigDecimal("0.0100");

    /** Currencies the pools run in. A pool is money, so each currency is its own set of prizes. */
    private List<String> currencies = List.of("EUR");

    /** Per-tier seed and share. Every tier in {@link JackpotTier} must appear. */
    private Map<JackpotTier, Tier> tiers = defaultTiers();

    /**
     * How much of one contribution each tier receives. Must sum to 1: a split that does not is either
     * quietly losing money that players paid for or minting money nobody did, and both are worse than
     * failing to start.
     */
    public void validate() {
        BigDecimal total = BigDecimal.ZERO;
        for (JackpotTier tier : JackpotTier.values()) {
            Tier config = tiers.get(tier);
            if (config == null) {
                throw new IllegalStateException("rgs.jackpot.tiers is missing tier " + tier);
            }
            if (config.getSeed() == null || config.getSeed().signum() <= 0) {
                throw new IllegalStateException(
                        "rgs.jackpot.tiers." + tier + ".seed must be > 0, found " + config.getSeed());
            }
            if (config.getShare() == null || config.getShare().signum() < 0) {
                throw new IllegalStateException(
                        "rgs.jackpot.tiers." + tier + ".share must be >= 0, found " + config.getShare());
            }
            if (config.getAwardOneInN() < 1) {
                throw new IllegalStateException(
                        "rgs.jackpot.tiers." + tier + ".award-one-in-n must be >= 1, found "
                                + config.getAwardOneInN());
            }
            total = total.add(config.getShare());
        }
        if (total.compareTo(BigDecimal.ONE) != 0) {
            throw new IllegalStateException(
                    "rgs.jackpot.tiers shares must sum to exactly 1, found " + total.toPlainString());
        }
        if (defaultContributionRate == null || defaultContributionRate.signum() < 0
                || defaultContributionRate.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalStateException(
                    "rgs.jackpot.default-contribution-rate must be in [0, 1], found "
                            + defaultContributionRate);
        }
    }

    public Tier tier(JackpotTier tier) {
        return tiers.get(tier);
    }

    @Getter
    @Setter
    public static class Tier {

        /** What the pool is worth when it has never been won, and what it returns to when it is. */
        private BigDecimal seed;

        /**
         * This tier's cut of each contribution. The ladder is deliberately top-light: the Mega grows
         * slowest because it is meant to be rare and large, and a Mega fed at the same rate as the Mini
         * would be neither.
         */
        private BigDecimal share;

        /**
         * Odds of this tier landing on a contributing spin, as 1 in N.
         *
         * <p>A flat per-spin chance rather than a must-hit-by ceiling or a stake-weighted draw. Both of
         * those are more realistic and neither is more <em>demonstrable</em>: what this feature has to
         * show is that a win is paid once and the pool resets, and a rule a viewer can hold in their
         * head makes that the subject rather than the odds model.
         */
        private int awardOneInN;
    }

    private static Map<JackpotTier, Tier> defaultTiers() {
        Map<JackpotTier, Tier> defaults = new EnumMap<>(JackpotTier.class);
        defaults.put(JackpotTier.MINI, tier("10.00", "0.40", 100));
        defaults.put(JackpotTier.MINOR, tier("100.00", "0.30", 500));
        defaults.put(JackpotTier.MAJOR, tier("1000.00", "0.20", 2_500));
        defaults.put(JackpotTier.MEGA, tier("10000.00", "0.10", 10_000));
        return defaults;
    }

    private static Tier tier(String seed, String share, int awardOneInN) {
        Tier t = new Tier();
        t.setSeed(new BigDecimal(seed));
        t.setShare(new BigDecimal(share));
        t.setAwardOneInN(awardOneInN);
        return t;
    }
}
