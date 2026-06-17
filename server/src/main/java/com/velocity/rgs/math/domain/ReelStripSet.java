package com.velocity.rgs.math.domain;

/**
 * Reel strip selector per A.4. {@code POWER_BET} is used when the Power Bet feature is active;
 * {@code FREE_SPINS} is the high-RTP set used inside the free-spins loop.
 */
public enum ReelStripSet {
    BASE,
    POWER_BET,
    FREE_SPINS
}
