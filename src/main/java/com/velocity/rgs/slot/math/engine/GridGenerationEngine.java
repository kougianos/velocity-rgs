package com.velocity.rgs.slot.math.engine;

import com.velocity.rgs.slot.math.config.SlotMathDefinition;
import com.velocity.rgs.slot.math.domain.ReelStrip;
import com.velocity.rgs.slot.math.domain.ReelStripSet;
import com.velocity.rgs.rng.RandomNumberGenerator;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * Builds the visible {@code rows × cols} matrix for a single spin (Milestone 2 Task 2.3).
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
}
