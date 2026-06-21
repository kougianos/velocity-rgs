package com.velocity.rgs.audit.replay;

import com.velocity.rgs.math.engine.WinLine;
import com.velocity.rgs.rng.RngDraw;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Result of replaying a single {@code game_round} (A.16 / M6 Task 6.2). The reconstruction is bit-exact
 * if the engine is deterministic given the recorded RNG draws.
 */
public record RoundReplayResult(
        String roundId,
        String sessionId,
        String playerId,
        String gameId,
        String mathVersion,
        String reelStripSet,
        boolean powerBetActive,
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
        Instant originalCreatedAt
) {
}
