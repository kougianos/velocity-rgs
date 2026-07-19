package com.velocity.rgs.slot.math.domain;

/**
 * Canonical bonus-buy types (per A.0.1 / A.4 {@code bonusBuyOptions.buyType}).
 */
public enum BonusBuyType {
    FREE_SPINS_BUY,
    PICK_COLLECT_BUY,
    /** Buys straight into Hold &amp; Spin with the trigger's worth of coins already locked. */
    HOLD_SPIN_BUY
}
