package com.velocity.rgs.audit.replay;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocity.rgs.common.error.ErrorCode;
import com.velocity.rgs.common.error.RgsException;
import com.velocity.rgs.slot.domain.GameRound;
import com.velocity.rgs.slot.persistence.GameRoundRepository;
import com.velocity.rgs.slot.math.config.SlotMathDefinition;
import com.velocity.rgs.slot.math.config.SlotMathRegistry;
import com.velocity.rgs.slot.math.domain.ReelStripSet;
import com.velocity.rgs.slot.math.engine.EvaluationResult;
import com.velocity.rgs.slot.math.engine.GridGenerationEngine;
import com.velocity.rgs.slot.math.engine.GridGenerationResult;
import com.velocity.rgs.slot.math.engine.ReelEvaluator;
import com.velocity.rgs.rng.DeterministicReplayRng;
import com.velocity.rgs.rng.RngDraw;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

/**
 * Bit-exact round reconstruction (M6 Task 6.2 / A.16). Loads the persisted {@link GameRound},
 * replays the recorded RNG draws through {@link DeterministicReplayRng} into the same
 * {@link GridGenerationEngine}, then re-evaluates with {@link ReelEvaluator}. The reconstructed matrix
 * MUST equal the stored matrix; divergence is reported in the {@link RoundReplayResult} for the caller
 * (and asserted via {@link IllegalStateException} when the matrix differs).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReplayService {

    private final GameRoundRepository roundRepository;
    private final SlotMathRegistry mathRegistry;
    private final GridGenerationEngine gridEngine;
    private final ReelEvaluator reelEvaluator;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public RoundReplayResult replay(String roundId) {
        GameRound round = roundRepository.findByRoundId(roundId)
                .orElseThrow(() -> new RgsException(ErrorCode.SESSION_NOT_FOUND,
                        "Round not found: " + roundId));

        SlotMathDefinition math = mathRegistry.require(round.getGameId(), round.getMathVersion());
        List<RngDraw> draws = deserializeDraws(round.getRngDraws());
        int[][] originalMatrix = deserializeMatrix(round.getMatrix());
        int[] originalStops = deserializeStops(round.getStopPositions());

        ReelStripSet stripSet = inferStripSet(round);
        DeterministicReplayRng replayRng = new DeterministicReplayRng(draws);
        GridGenerationResult grid = gridEngine.generate(math, stripSet, replayRng);
        EvaluationResult evaluation = reelEvaluator.evaluate(grid.matrix(), round.getBetAmount(), math);

        boolean matrixMatches = Arrays.deepEquals(originalMatrix, grid.matrix())
                && Arrays.equals(originalStops, grid.stopPositions());
        boolean totalWinMatches = evaluation.totalWin().compareTo(round.getTotalWin()) == 0;

        if (!matrixMatches) {
            log.error("Replay matrix mismatch for round {}: original={} reconstructed={}",
                    roundId, Arrays.deepToString(originalMatrix),
                    Arrays.deepToString(grid.matrix()));
            throw new RgsException(ErrorCode.INTERNAL_ERROR,
                    "Replay matrix mismatch for round " + roundId);
        }

        return new RoundReplayResult(
                round.getRoundId(),
                round.getSessionId(),
                round.getPlayerId(),
                round.getGameId(),
                round.getMathVersion(),
                stripSet.name(),
                round.isPowerBetActive(),
                round.getBetAmount(),
                round.getTotalWin(),
                evaluation.totalWin(),
                originalMatrix,
                grid.matrix(),
                originalStops,
                grid.stopPositions(),
                evaluation.winLines(),
                draws,
                matrixMatches,
                totalWinMatches,
                round.getCreatedAt()
        );
    }

    private ReelStripSet inferStripSet(GameRound round) {
        // No debit recorded ⇒ free-spin iteration (per SlotEngineService.spin)
        if (round.getBetTransactionId() == null) {
            return ReelStripSet.FREE_SPINS;
        }
        return round.isPowerBetActive() ? ReelStripSet.POWER_BET : ReelStripSet.BASE;
    }

    private List<RngDraw> deserializeDraws(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<RngDraw>>() {});
        } catch (Exception ex) {
            throw new RgsException(ErrorCode.INTERNAL_ERROR,
                    "Cannot deserialize rng_draws: " + ex.getMessage(), ex);
        }
    }

    private int[][] deserializeMatrix(String json) {
        try {
            return objectMapper.readValue(json, int[][].class);
        } catch (Exception ex) {
            throw new RgsException(ErrorCode.INTERNAL_ERROR,
                    "Cannot deserialize matrix: " + ex.getMessage(), ex);
        }
    }

    private int[] deserializeStops(String json) {
        try {
            return objectMapper.readValue(json, int[].class);
        } catch (Exception ex) {
            throw new RgsException(ErrorCode.INTERNAL_ERROR,
                    "Cannot deserialize stop_positions: " + ex.getMessage(), ex);
        }
    }
}
