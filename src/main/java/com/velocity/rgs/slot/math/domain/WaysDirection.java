package com.velocity.rgs.slot.math.domain;

/**
 * Which way a ways run may be anchored. Selected per game via {@code math.waysDirection}; absent means
 * {@link #LEFT_TO_RIGHT}, which is what every ways game authored before this existed uses.
 */
public enum WaysDirection {

    /** Runs start on reel 0 and extend rightwards. The conventional 243-ways model. */
    LEFT_TO_RIGHT,

    /**
     * Win both ways: a run may be anchored on reel 0 <em>or</em> on the rightmost reel, and a symbol can
     * pay in both directions on the same screen. Roughly doubles hit frequency, so a game switching to
     * it needs its pay table re-calibrated - it is not a free upgrade.
     */
    BOTH_WAYS
}
