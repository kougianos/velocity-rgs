package com.velocity.rgs.slot.math.engine;

import com.velocity.rgs.slot.math.config.SlotMathDefinition;
import com.velocity.rgs.slot.math.domain.ReelStrip;
import com.velocity.rgs.slot.math.domain.ReelStripSet;
import com.velocity.rgs.rng.RandomNumberGenerator;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * Builds the visible {@code rows × cols} matrix for a single spin (Milestone 2 Task 2.3), and refills it
 * between cascade drops.
 *
 * <p>For each reel (column) the engine draws one stop index in {@code [0, stripLength)} from the
 * supplied {@link RandomNumberGenerator} and reads {@code rows} consecutive symbols starting at that
 * stop, wrapping at the end of the strip. The selected {@link ReelStripSet} dictates which strip
 * variant is used (BASE / POWER_BET / FREE_SPINS, per Appendix A.4).
 *
 * <p>Stateless and side-effect free with respect to its inputs; all randomness flows through the
 * supplied RNG, whose draw history is therefore the complete reproducibility record (see A.11).
 */
@Component
public class GridGenerationEngine {

    public GridGenerationResult generate(SlotMathDefinition math, ReelStripSet stripSet,
                                         RandomNumberGenerator rng) {
        Objects.requireNonNull(math, "math");
        Objects.requireNonNull(stripSet, "stripSet");
        Objects.requireNonNull(rng, "rng");

        int rows = math.grid().rows();
        int cols = math.grid().cols();
        List<ReelStrip> strips = math.reelStrips().get(stripSet);

        int[] stopPositions = new int[cols];
        int[][] matrix = new int[rows][cols];
        for (int c = 0; c < cols; c++) {
            ReelStrip strip = strips.get(c);
            int stop = rng.nextIndex(strip.length());
            stopPositions[c] = stop;
            for (int r = 0; r < rows; r++) {
                matrix[r][c] = strip.symbolAt(stop + r);
            }
        }
        return new GridGenerationResult(matrix, stopPositions);
    }

    /**
     * Removes the masked cells, drops the survivors to the bottom of their column, and refills the gap
     * from the top - one drawn symbol per cleared cell.
     *
     * <p><strong>Every refill symbol is drawn from the {@code rng} handed in</strong>, which is the
     * round's RNG and therefore the one wired to the round's {@link com.velocity.rgs.rng.RngDrawSink}.
     * That is the whole correctness requirement of cascades: {@code game_round.rng_draws} has to contain
     * the refill draws in the same order the engine consumed them, or
     * {@link com.velocity.rgs.rng.DeterministicReplayRng} runs out of draws (or, worse, silently
     * reproduces a different board) and the round stops being replayable. Never construct a fresh RNG or
     * a {@code discarding()} sink here.
     *
     * <p>Draw order is fixed and part of the replay contract: columns left to right, and within a column
     * top-down, so the first draw fills the topmost gap of reel 0.
     *
     * @param cleared {@code [rows][cols]} mask of cells removed by the paying drop
     * @return the refilled grid plus the strip indices drawn, in draw order
     */
    public GridGenerationResult refill(SlotMathDefinition math, ReelStripSet stripSet,
                                       RandomNumberGenerator rng, int[][] grid, boolean[][] cleared) {
        Objects.requireNonNull(math, "math");
        Objects.requireNonNull(stripSet, "stripSet");
        Objects.requireNonNull(rng, "rng");
        Objects.requireNonNull(grid, "grid");
        Objects.requireNonNull(cleared, "cleared");

        int rows = math.grid().rows();
        int cols = math.grid().cols();
        List<ReelStrip> strips = math.reelStrips().get(stripSet);

        int[][] next = new int[rows][cols];
        int[] draws = new int[countCleared(cleared, rows, cols)];
        int drawCursor = 0;

        for (int c = 0; c < cols; c++) {
            // Survivors keep their relative order and settle at the bottom of the column.
            int write = rows - 1;
            for (int r = rows - 1; r >= 0; r--) {
                if (!cleared[r][c]) {
                    next[write--][c] = grid[r][c];
                }
            }
            // Everything above them is new, filled top-down so the draw order matches the visual one.
            ReelStrip strip = strips.get(c);
            for (int r = 0; r <= write; r++) {
                int index = rng.nextIndex(strip.length());
                draws[drawCursor++] = index;
                next[r][c] = strip.symbolAt(index);
            }
        }
        return new GridGenerationResult(next, draws);
    }

    /**
     * Re-draws every cell that is not held, leaving the held ones showing {@code heldSymbolId}. This is
     * the Hold &amp; Spin respin: coins stay put and everything around them is drawn afresh, in place -
     * unlike {@link #refill}, nothing drops, because nothing was removed.
     *
     * <p>Like refills, every draw comes from the {@code rng} handed in, and the order is fixed for
     * replay: columns left to right, rows top-down.
     *
     * @param held         {@code [rows][cols]} mask of locked cells
     * @param heldSymbolId the symbol locked cells display (the game's coin)
     */
    public GridGenerationResult respin(SlotMathDefinition math, ReelStripSet stripSet,
                                       RandomNumberGenerator rng, boolean[][] held, int heldSymbolId) {
        Objects.requireNonNull(math, "math");
        Objects.requireNonNull(stripSet, "stripSet");
        Objects.requireNonNull(rng, "rng");
        Objects.requireNonNull(held, "held");

        int rows = math.grid().rows();
        int cols = math.grid().cols();
        List<ReelStrip> strips = math.reelStrips().get(stripSet);

        int[][] matrix = new int[rows][cols];
        int[] draws = new int[rows * cols - countCleared(held, rows, cols)];
        int drawCursor = 0;

        for (int c = 0; c < cols; c++) {
            ReelStrip strip = strips.get(c);
            for (int r = 0; r < rows; r++) {
                if (held[r][c]) {
                    matrix[r][c] = heldSymbolId;
                    continue;
                }
                int index = rng.nextIndex(strip.length());
                draws[drawCursor++] = index;
                matrix[r][c] = strip.symbolAt(index);
            }
        }
        return new GridGenerationResult(matrix, draws);
    }

    private static int countCleared(boolean[][] cleared, int rows, int cols) {
        int n = 0;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (cleared[r][c]) {
                    n++;
                }
            }
        }
        return n;
    }
}
