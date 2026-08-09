package com.velocity.rgs.jackpot.domain;

/**
 * The four progressive tiers, shared across every slot (§2).
 *
 * <p>Shared on purpose. A per-game jackpot on a portfolio this size would be four pools each fed by a
 * sixth of the traffic, which is four jackpots that never reach a number worth winning. One ladder fed
 * by every game is how a progressive actually behaves, and it is also the only version of this feature
 * that has anything to show after a few minutes of demo play.
 *
 * <p>Declared smallest first, and the order is used for display - a player reads a jackpot ladder
 * upwards.
 *
 * <p>Not to be confused with {@code RespinJackpot}, the Hold &amp; Spin tiers. Those are fixed
 * multipliers of the stake that a single game pays out of its own RTP; these are pooled money that
 * every game contributes to. They share the word and nothing else, which is exactly why this enum lives
 * in its own package rather than beside the slot math.
 */
public enum JackpotTier {

    MINI,
    MINOR,
    MAJOR,
    MEGA;

    /** Title case for display, so the client is not doing string surgery on an enum name. */
    public String label() {
        return name().charAt(0) + name().substring(1).toLowerCase(java.util.Locale.ROOT);
    }
}
