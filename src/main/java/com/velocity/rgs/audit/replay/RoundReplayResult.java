package com.velocity.rgs.audit.replay;

import com.velocity.rgs.slot.math.engine.CascadeStep;
import com.velocity.rgs.slot.math.engine.WinLine;
import com.velocity.rgs.rng.RngDraw;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Result of replaying a single {@code game_round} (A.16 / M6 Task 6.2). The reconstruction is bit-exact
 * if the engine is deterministic given the recorded RNG draws.
 *
 * <p>The flat {@code originalMatrix} / {@code reconstructedMatrix} pair is the round's <em>opening</em>
 * board, kept because it is what every existing consumer of this record reads. A cascading round has
 * more to say, so {@code originalSequence} / {@code reconstructedSequence} carry every drop -
 * {@code matrixMatches} is asserted across the whole of both, not just the first entry.
 *
 * @param carriedWildMode  {@code WALKING} or {@code STICKY} when the round's wilds carry between spins,
 *                         null otherwise. Distinct from an empty {@code carriedWildCells}, which on such
 *                         a round means the recorded carry was empty - the first spin of the feature
 * @param carriedWildCells {@code [row, col]} of every wild the spin did not draw - held over from the
 *                         previous spin of a sticky feature, and already stepped left for a walking one.
 *                         Empty for all but a sticky/walking wild round. Reported because the transform
 *                         flattens carried and drawn wilds into one grid, and it is precisely the
 *                         carried ones that the round had to record to be replayable at all
 * @param featureWinBeforeThisSpin what earlier spins of the same free-spins feature had won when this
 *                         one began, and {@code featureBuyMultiplier} the boost a bonus buy attached to
 *                         the total. Both non-null only on the spin that <em>settled</em> a feature,
 *                         where the round's payout is the whole feature rather than this board's lines -
 *                         which is the only case in which those two numbers differ
 */
public record RoundReplayResult(
        String roundId,
        String sessionId,
        String playerId,
        String gameId,
        String mathVersion,
        String reelStripSet,
        boolean powerBetActive,
        String currency,
        BigDecimal betAmount,
        BigDecimal originalTotalWin,
        BigDecimal reconstructedTotalWin,
        int[][] originalMatrix,
        int[][] reconstructedMatrix,
        int[] originalStopPositions,
        int[] reconstructedStopPositions,
        List<WinLine> reconstructedWinLines,
        List<RngDraw> rngDraws,
        boolean matrixMatches,
        boolean totalWinMatches,
        List<int[][]> originalSequence,
        List<CascadeStep> reconstructedSequence,
        String carriedWildMode,
        List<int[]> carriedWildCells,
        BigDecimal featureWinBeforeThisSpin,
        BigDecimal featureBuyMultiplier,
        Instant originalCreatedAt
) {

    /** Number of drops in the round: 1 for a conventional spin, more when it tumbled. */
    public int steps() {
        return reconstructedSequence == null ? 0 : reconstructedSequence.size();
    }
}
