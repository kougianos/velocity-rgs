package com.velocity.rgs.slot.math.engine;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * One drop of a round: the grid as it stood, what it paid, and which cells it cleared on the way to the
 * next drop. A non-cascading game produces exactly one of these; a cascading game produces one per drop,
 * ending with the settled (non-paying) grid the player is left looking at.
 *
 * <p>The sequence of steps <em>is</em> the round's visual record - it is what
 * {@code game_round.matrix} / {@code stop_positions} persist and what the client replays as an
 * animation - so every field here has to be reconstructable from the round's RNG draws alone.
 *
 * @param index            0 for the initial drop, then 1, 2, … per refill
 * @param grid             the board this step was evaluated against, indexed {@code [row][reel]}
 * @param stopPositions    the strip indices drawn to produce {@code grid}. For step 0 that is one reel
 *                         stop per column, exactly as a non-cascading spin records. For a refill step it
 *                         is one index per <em>refilled cell</em> in draw order (top-down within a
 *                         column, columns left to right) - a refill has no reel stops, and the drawn
 *                         indices are what actually reproduce it.
 * @param winLines         wins on this grid, with {@code payout} already scaled by {@code multiplier}
 * @param multiplier       the progressive multiplier this step paid at, from
 *                         {@link com.velocity.rgs.slot.math.config.CascadeConfig#multiplierFor(int)}
 * @param stepWin          this step's contribution to the round, after {@code multiplier}
 * @param clearedPositions {@code [row, col]} pairs removed by this step's wins and refilled for the next
 *                         step; empty on the final step, which by definition paid nothing
 */
public record CascadeStep(
        int index,
        int[][] grid,
        int[] stopPositions,
        List<WinLine> winLines,
        BigDecimal multiplier,
        BigDecimal stepWin,
        int[][] clearedPositions
) {

    public CascadeStep {
        Objects.requireNonNull(grid, "grid");
        Objects.requireNonNull(stopPositions, "stopPositions");
        Objects.requireNonNull(multiplier, "multiplier");
        Objects.requireNonNull(stepWin, "stepWin");
        if (index < 0) {
            throw new IllegalArgumentException("index must be >= 0, found " + index);
        }
        winLines = winLines == null ? List.of() : List.copyOf(winLines);
        clearedPositions = clearedPositions == null ? new int[0][] : clearedPositions;
    }
}
