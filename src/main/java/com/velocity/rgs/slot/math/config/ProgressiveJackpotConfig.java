package com.velocity.rgs.slot.math.config;

import java.math.BigDecimal;

/**
 * Whether this game feeds the shared progressive pools, and at what rate (§2).
 *
 * <p>The {@code math.progressiveJackpot} block of {@code games/<gameId>/<mathVersion>.json}. Absent
 * means {@link #disabled()}, which is how a game opts out: it contributes nothing and the lobby shows
 * no Progressive Jackpots card for it. That biconditional is the same property cascades and Hold &amp;
 * Spin already have - the shop window cannot advertise a mechanic the engine does not run, because the
 * same block drives both.
 *
 * <p>Only the <em>rate</em> lives here. The tiers, their seeds and the split between them are platform
 * config ({@code rgs.jackpot.*}), because the pools are shared across every game and no single game may
 * own numbers that every other game also pays into.
 *
 * @param enabled          whether spins on this game contribute
 * @param contributionRate this game's fraction of stake, or null to take the platform default. An
 *                         override exists because a jackpot-themed game reasonably feeds the pools
 *                         harder than a game that merely happens to be on the same platform
 */
public record ProgressiveJackpotConfig(
        boolean enabled,
        BigDecimal contributionRate
) {

    /** A game that does not feed the pools and does not advertise them. */
    public static ProgressiveJackpotConfig disabled() {
        return new ProgressiveJackpotConfig(false, null);
    }

    public ProgressiveJackpotConfig {
        if (enabled && contributionRate != null) {
            if (contributionRate.signum() < 0 || contributionRate.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException(
                        "progressiveJackpot.contributionRate must be in [0, 1], found " + contributionRate);
            }
        }
        if (!enabled && contributionRate != null) {
            throw new IllegalArgumentException(
                    "progressiveJackpot.contributionRate is set but the block is disabled - a rate that "
                            + "never applies is config that lies about what the game does");
        }
    }

    /** This game's effective rate: its own override, else the platform default. */
    public BigDecimal rateOr(BigDecimal platformDefault) {
        return contributionRate != null ? contributionRate : platformDefault;
    }
}
