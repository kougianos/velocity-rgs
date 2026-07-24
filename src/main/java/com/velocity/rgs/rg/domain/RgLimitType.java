package com.velocity.rgs.rg.domain;

/**
 * Which rule stopped play. Carried on the {@code RG_LIMIT_EXCEEDED} error so the client can name the
 * limit that fired rather than showing one generic message for five different rules - an error taxonomy
 * nobody can tell apart is indistinguishable from not having one.
 */
public enum RgLimitType {

    /** Minutes of continuous play exhausted. */
    SESSION_DURATION,

    /** Net loss over the period reached the player's limit. */
    LOSS,

    /** Total staked over the period reached the player's limit. */
    WAGER,

    /**
     * A voluntary break the player asked for, still running. Not a limit in the accounting sense, but
     * it reaches the player the same way and answers the same question - what stopped me, and when can
     * I play again - so it travels on the same code rather than inventing a third.
     */
    COOL_OFF
}
