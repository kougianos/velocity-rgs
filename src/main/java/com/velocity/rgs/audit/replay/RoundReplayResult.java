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
        Instant originalCreatedAt
) {

    /** Number of drops in the round: 1 for a conventional spin, more when it tumbled. */
    public int steps() {
        return reconstructedSequence == null ? 0 : reconstructedSequence.size();
    }
}
