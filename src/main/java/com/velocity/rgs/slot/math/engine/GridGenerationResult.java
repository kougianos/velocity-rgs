package com.velocity.rgs.slot.math.engine;

/**
 * Output of {@link GridGenerationEngine}: the {@code rows × cols} symbol matrix that lands on the
 * visible window and the per-reel stop position (zero-indexed offset into the reel strip).
 *
 * <p>Matrix convention: {@code matrix[row][col]}, with row 0 at the top of the window (matches the
 * coordinate system used by paylines in Appendix A.4).
 *
 * <p>The {@code stopPositions} array is wire-aligned with the {@code stopPositions} field in the
 * spin response (Appendix A.7).
 */
public record GridGenerationResult(int[][] matrix, int[] stopPositions) {

    public GridGenerationResult {
        if (matrix == null || stopPositions == null) {
            throw new IllegalArgumentException("matrix and stopPositions are required");
        }
    }
}
