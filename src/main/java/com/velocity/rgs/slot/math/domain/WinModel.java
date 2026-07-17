package com.velocity.rgs.slot.math.domain;

/**
 * How a game turns a grid into wins. Selected per game via the {@code winModel} field of the
 * {@code math} block; absent means {@link #PAYLINES}, which is what the originally shipped games use.
 */
public enum WinModel {

    /**
     * Fixed paylines. The stake is split across the configured lines ({@code lineBet = bet / lines})
     * and each line pays independently. Requires a non-empty {@code paylines} list.
     */
    PAYLINES,

    /**
     * Ways-to-win: every left-to-right path through the grid is live, so a 3-row x 5-reel grid has
     * 3^5 = 243 ways. The stake is split across all ways ({@code wayBet = bet / rows^cols}), which
     * keeps a ways pay table in the same units as a payline one. Requires an empty {@code paylines}
     * list - coordinates are implied by the grid.
     */
    WAYS
}
