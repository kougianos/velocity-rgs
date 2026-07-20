package com.velocity.rgs.audit.replay;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocity.rgs.common.error.ErrorCode;
import com.velocity.rgs.common.error.RgsException;
import com.velocity.rgs.slot.domain.GameRound;
import com.velocity.rgs.slot.domain.RoundKind;
import com.velocity.rgs.slot.feature.respin.RespinEngine;
import com.velocity.rgs.slot.feature.respin.RespinPayloadCodec;
import com.velocity.rgs.slot.feature.respin.RespinState;
import com.velocity.rgs.slot.persistence.GameRoundRepository;
import com.velocity.rgs.slot.persistence.RoundGridCodec;
import com.velocity.rgs.slot.math.config.SlotMathDefinition;
import com.velocity.rgs.slot.math.config.SlotMathRegistry;
import com.velocity.rgs.slot.math.config.WildFeatureConfig;
import com.velocity.rgs.slot.math.engine.WildFeatureEngine;
import com.velocity.rgs.slot.math.domain.ReelStripSet;
import com.velocity.rgs.slot.math.engine.CascadeStep;
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

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Bit-exact round reconstruction (M6 Task 6.2 / A.16). Loads the persisted {@link GameRound},
 * replays the recorded RNG draws through {@link DeterministicReplayRng} into the same
 * {@link GridGenerationEngine}, then re-evaluates with {@link ReelEvaluator}. The reconstructed
 * sequence MUST equal the stored one; divergence is reported in the {@link RoundReplayResult} for the
 * caller (and asserted via an exception when the grids differ).
 *
 * <p>Not every persisted round can be rebuilt from its own draws. Two cannot, and both are refused with
 * {@link ErrorCode#ROUND_NOT_REPLAYABLE} and a reason rather than being reported as a mismatch: a respin
 * recorded before {@code feature_context} existed, and any round played with sticky or walking wilds,
 * whose carry lives on the session. Reserving {@code INTERNAL_ERROR} for genuine divergence is what
 * keeps a 500 from here worth reading.
 *
 * <p>A cascading round is reconstructed the same way and is the strictest test of the whole scheme:
 * every drop after the first is built from refill draws, so the replay only lands if those draws were
 * captured in the round's sink, in the order the engine consumed them. The comparison is therefore over
 * the full sequence - matching only the opening board would pass a round whose tumble diverged.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReplayService {

    private final GameRoundRepository roundRepository;
    private final SlotMathRegistry mathRegistry;
    private final GridGenerationEngine gridEngine;
    private final ReelEvaluator reelEvaluator;
    private final RoundGridCodec gridCodec;
    private final RespinEngine respinEngine;
    private final RespinPayloadCodec respinPayloadCodec;
    private final WildFeatureEngine wildFeatureEngine;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public RoundReplayResult replay(String roundId) {
        GameRound round = roundRepository.findByRoundId(roundId)
                .orElseThrow(() -> new RgsException(ErrorCode.SESSION_NOT_FOUND,
                        "Round not found: " + roundId));

        SlotMathDefinition math = mathRegistry.require(round.getGameId(), round.getMathVersion());
        List<RngDraw> draws = deserializeDraws(round.getRngDraws());
        List<int[][]> originalMatrices = gridCodec.readMatrices(round.getMatrix());
        List<int[]> originalStops = gridCodec.readStopPositions(round.getStopPositions());

        ReelStripSet stripSet = inferStripSet(round);
        DeterministicReplayRng replayRng = new DeterministicReplayRng(draws);

        // A respin is not a reel spin: it re-draws only the cells that were not holding a coin, so it
        // is reconstructed from the coins recorded in feature_context rather than from nothing.
        // Replaying it down the spin path fails on the very first draw - the bounds do not even match.
        List<CascadeStep> steps;
        EvaluationResult evaluation;
        if (round.getRoundKind() == RoundKind.RESPIN) {
            steps = replayRespin(round, math, replayRng);
            evaluation = null;
        } else {
            requireReconstructableWilds(round, math, stripSet);
            GridGenerationResult opening = gridEngine.generate(math, stripSet, replayRng);
            // Wild features reshape the board before anything evaluates it, and it is the reshaped board
            // that gets persisted - so the replay has to make the same transform or it compares the
            // drawn grid against a board that was never only drawn. Carried wilds are empty here by
            // construction: requireReconstructableWilds above has already refused any round that needed
            // them. Refills inside evaluateRound are left untransformed, matching SlotEngineService,
            // which applies wilds once to the opening grid.
            int[][] board = wildFeatureEngine.apply(opening.matrix(), math, stripSet, List.of()).matrix();
            evaluation = reelEvaluator.evaluateRound(board,
                    opening.stopPositions(), round.getBetAmount(), math, stripSet, replayRng);
            steps = evaluation.steps();
        }
        boolean matrixMatches = sequencesMatch(originalMatrices, originalStops, steps);
        // A respin's payout is settled by the feature, not by evaluating its board, so there is no
        // reconstructed line win to compare - the board matching is the whole claim it makes.
        BigDecimal reconstructedWin = evaluation == null ? round.getTotalWin() : evaluation.totalWin();
        boolean totalWinMatches = reconstructedWin.compareTo(round.getTotalWin()) == 0;

        if (!matrixMatches) {
            log.error("Replay sequence mismatch for round {}: originalSteps={} reconstructedSteps={} "
                            + "original={} reconstructed={}",
                    roundId, originalMatrices.size(), steps.size(),
                    render(originalMatrices), renderSteps(steps));
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
                round.getCurrency(),
                round.getBetAmount(),
                round.getTotalWin(),
                reconstructedWin,
                originalMatrices.get(0),
                steps.get(0).grid(),
                originalStops.get(0),
                steps.get(0).stopPositions(),
                evaluation == null ? List.of() : evaluation.winLines(),
                draws,
                matrixMatches,
                totalWinMatches,
                originalMatrices,
                steps,
                round.getCreatedAt()
        );
    }

    /**
     * Rebuilds one Hold &amp; Spin respin: take the coins recorded in {@code feature_context}, feed the
     * recorded draws back through {@link RespinEngine}, and the board that comes out must be the one
     * that was persisted.
     *
     * <p>Respins always draw from the BASE strips (see {@code SlotEngineService.respinSpin}), which is
     * why the strip set is fixed here rather than inferred - a respin has no bet transaction, so the
     * usual inference would call it a free spin and read the wrong strips.
     */
    private List<CascadeStep> replayRespin(GameRound round, SlotMathDefinition math,
                                           DeterministicReplayRng replayRng) {
        if (round.getFeatureContext() == null || round.getFeatureContext().isBlank()) {
            // A round recorded before feature_context existed. Not a server fault and not something a
            // retry fixes - the input state was never captured - so it answers 409 with the reason
            // rather than a 500 that reads like the replay engine broke.
            throw new RgsException(ErrorCode.ROUND_NOT_REPLAYABLE,
                    "Round " + round.getRoundId() + " is a Hold & Spin respin recorded before its "
                            + "feature context was captured, so the coins held going into it are not "
                            + "known and the round cannot be reconstructed");
        }
        RespinState before = respinPayloadCodec.decode(deserializeMap(round.getFeatureContext()));
        RespinEngine.RespinOutcome outcome = respinEngine.respin(before, math, ReelStripSet.BASE, replayRng);
        return List.of(new CascadeStep(0, outcome.matrix(), new int[0], List.of(),
                BigDecimal.ONE, BigDecimal.ZERO, new int[0][]));
    }

    /**
     * Refuses, up front, a round whose wild transform depended on state this round does not carry.
     *
     * <p>Expanding wilds are a pure function of the drawn grid, so they replay: the transform is simply
     * re-applied. Sticky and walking wilds are not - they are seeded from wilds held over on the
     * <em>session</em> ({@code active_feature_payload}), which no round records, so the board that was
     * persisted is not derivable from this round's draws at any price. That is the same shortfall
     * {@code feature_context} was added to fix for Hold &amp; Spin respins (V12), and the same fix is
     * owed here.
     *
     * <p>Refused before replaying rather than after comparing, and refused for every spin of such a
     * feature - including the first, whose carry happens to be empty and which would therefore have
     * reconstructed. An audit surface should decline to claim a proof it cannot guarantee, and "it
     * worked that time" is not a guarantee. A mismatch reaching {@code sequencesMatch} then means what
     * it should: the engine genuinely diverged.
     */
    private void requireReconstructableWilds(GameRound round, SlotMathDefinition math,
                                             ReelStripSet stripSet) {
        WildFeatureConfig wilds = math.wildFeatures();
        if (!wilds.appliesOn(stripSet) || !(wilds.sticky() || wilds.walking())) {
            return;
        }
        throw new RgsException(ErrorCode.ROUND_NOT_REPLAYABLE,
                "Round " + round.getRoundId() + " was played on " + stripSet + " with "
                        + (wilds.walking() ? "walking" : "sticky") + " wilds, whose carried state lives "
                        + "on the session rather than in the round, so the board cannot be rebuilt from "
                        + "this round's draws alone");
    }

    /**
     * Every drop's grid and draws must line up, and there must be the same number of them: a round that
     * tumbled three times cannot be called reconstructed by a replay that tumbled twice.
     */
    private static boolean sequencesMatch(List<int[][]> originalMatrices, List<int[]> originalStops,
                                          List<CascadeStep> steps) {
        if (originalMatrices.size() != steps.size() || originalStops.size() != steps.size()) {
            return false;
        }
        for (int i = 0; i < steps.size(); i++) {
            if (!Arrays.deepEquals(originalMatrices.get(i), steps.get(i).grid())
                    || !Arrays.equals(originalStops.get(i), steps.get(i).stopPositions())) {
                return false;
            }
        }
        return true;
    }

    private static String render(List<int[][]> matrices) {
        return matrices.stream().map(Arrays::deepToString).toList().toString();
    }

    private static String renderSteps(List<CascadeStep> steps) {
        return steps.stream().map(s -> Arrays.deepToString(s.grid())).toList().toString();
    }

    private ReelStripSet inferStripSet(GameRound round) {
        // Respins always draw from BASE (per SlotEngineService.respinSpin) and, like free spins, carry
        // no bet transaction - so they have to be settled before the inference below, or the rule
        // "no debit ⇒ free spin" would mislabel every one of them.
        if (round.getRoundKind() == RoundKind.RESPIN) {
            return ReelStripSet.BASE;
        }
        // No debit recorded ⇒ free-spin iteration (per SlotEngineService.spin)
        if (round.getBetTransactionId() == null) {
            return ReelStripSet.FREE_SPINS;
        }
        return round.isPowerBetActive() ? ReelStripSet.POWER_BET : ReelStripSet.BASE;
    }

    private Map<String, Object> deserializeMap(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            throw new RgsException(ErrorCode.INTERNAL_ERROR,
                    "Cannot deserialize feature_context: " + ex.getMessage(), ex);
        }
    }

    private List<RngDraw> deserializeDraws(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<RngDraw>>() {});
        } catch (Exception ex) {
            throw new RgsException(ErrorCode.INTERNAL_ERROR,
                    "Cannot deserialize rng_draws: " + ex.getMessage(), ex);
        }
    }
}
