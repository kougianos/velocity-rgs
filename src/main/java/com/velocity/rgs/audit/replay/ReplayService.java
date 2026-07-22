package com.velocity.rgs.audit.replay;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocity.rgs.common.error.ErrorCode;
import com.velocity.rgs.common.error.RgsException;
import com.velocity.rgs.common.money.Money;
import com.velocity.rgs.session.domain.GameState;
import com.velocity.rgs.slot.domain.GameRound;
import com.velocity.rgs.slot.domain.RoundKind;
import com.velocity.rgs.slot.feature.freespins.FreeSpinsSettlementCodec;
import com.velocity.rgs.slot.feature.freespins.FreeSpinsSettlementCodec.FreeSpinsSettlement;
import com.velocity.rgs.slot.feature.respin.RespinEngine;
import com.velocity.rgs.slot.feature.respin.RespinPayloadCodec;
import com.velocity.rgs.slot.feature.respin.RespinState;
import com.velocity.rgs.slot.feature.wild.WildCarryPayloadCodec;
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
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bit-exact round reconstruction (M6 Task 6.2 / A.16). Loads the persisted {@link GameRound},
 * replays the recorded RNG draws through {@link DeterministicReplayRng} into the same
 * {@link GridGenerationEngine}, then re-evaluates with {@link ReelEvaluator}. The reconstructed
 * sequence MUST equal the stored one; divergence is reported in the {@link RoundReplayResult} for the
 * caller (and asserted via an exception when the grids differ).
 *
 * <p>Not every persisted round can be rebuilt from its own draws, and the ones that cannot are refused
 * with {@link ErrorCode#ROUND_NOT_REPLAYABLE} and a reason rather than being reported as a mismatch.
 * Both cases are the same case: a round whose input state was never written down - a respin recorded
 * before {@code feature_context} existed, or a sticky/walking wild spin recorded before that same column
 * started carrying the wilds held over into it. Anything written since stands on its own. Reserving
 * {@code INTERNAL_ERROR} for genuine divergence is what keeps a 500 from here worth reading.
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
    private final WildCarryPayloadCodec wildCarryPayloadCodec;
    private final FreeSpinsSettlementCodec freeSpinsSettlementCodec;
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
        WildCarry carry = WildCarry.NONE;
        List<WildFeatureEngine.WildCell> landedCarry = List.of();
        if (round.getRoundKind() == RoundKind.RESPIN) {
            steps = replayRespin(round, math, replayRng);
            evaluation = null;
        } else {
            carry = carriedWilds(round, math, stripSet);
            GridGenerationResult opening = gridEngine.generate(math, stripSet, replayRng);
            // Wild features reshape the board before anything evaluates it, and it is the reshaped board
            // that gets persisted - so the replay has to make the same transform or it compares the
            // drawn grid against a board that was never only drawn. That includes seeding it with the
            // carry the spin ran with, read back off the round rather than guessed at. Refills inside
            // evaluateRound are left untransformed, matching SlotEngineService, which applies wilds once
            // to the opening grid.
            WildFeatureEngine.WildOutcome wilds =
                    wildFeatureEngine.apply(opening.matrix(), math, stripSet, carry.cells());
            landedCarry = wilds.landedCarry();
            evaluation = reelEvaluator.evaluateRound(wilds.matrix(),
                    opening.stopPositions(), round.getBetAmount(), math, stripSet, replayRng);
            steps = evaluation.steps();
        }
        boolean matrixMatches = sequencesMatch(originalMatrices, originalStops, steps);
        // A respin's payout is settled by the feature, not by evaluating its board, so there is no
        // reconstructed line win to compare - the board matching is the whole claim it makes.
        FreeSpinsSettlement settlement = evaluation == null ? null : freeSpinsSettlement(round);
        BigDecimal reconstructedWin = evaluation == null
                ? round.getTotalWin()
                : reconstructPayout(round, settlement, evaluation.totalWin());
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
                carry.mode(),
                distinctCells(landedCarry),
                settlement != null && settlement.settled() ? settlement.accumulatedWinBefore() : null,
                settlement != null && settlement.settled() ? settlement.buyMultiplier() : null,
                round.getCreatedAt()
        );
    }

    /**
     * What this round paid, rebuilt: usually the win its own lines produced, but not on the spin that
     * ends a free-spins feature.
     *
     * <p>That last spin pays the <em>whole feature</em> - every earlier spin's win accumulated on the
     * session, times whatever boost a bonus buy attached at purchase. Comparing its own lines against
     * that is comparing two different quantities, and the answer was a public page reporting
     * "reconstruction diverged" on a round where the board had matched perfectly and nothing was
     * actually wrong. So the settlement is rebuilt from the running totals the round recorded, and a
     * settling round that recorded none is refused rather than judged against a number it was never
     * going to equal.
     *
     * <p>The scale and rounding here mirror {@code SlotEngineService.postProcessSpin} exactly. They have
     * to: this is a claim about money, so "close" is a failure.
     */
    private BigDecimal reconstructPayout(GameRound round, FreeSpinsSettlement settlement,
                                         BigDecimal ownWin) {
        if (settlement == null) {
            if (settlesAFreeSpinsFeature(round)) {
                throw new RgsException(ErrorCode.ROUND_NOT_REPLAYABLE,
                        "Round " + round.getRoundId() + " paid out a whole free-spins feature and "
                                + "predates the capture that records the running totals it settled - the "
                                + "wins accumulated by earlier spins are not on this round, so its payout "
                                + "cannot be rebuilt from this round's draws alone");
            }
            return ownWin;
        }
        if (!settlement.settled()) {
            return ownWin;
        }
        return settlement.payoutFor(ownWin)
                .setScale(Money.minorUnitScale(round.getCurrency()), RoundingMode.HALF_UP);
    }

    /** The free-spins running totals this round recorded, or null when it recorded none. */
    private FreeSpinsSettlement freeSpinsSettlement(GameRound round) {
        if (round.getFeatureContext() == null || round.getFeatureContext().isBlank()) {
            return null;
        }
        return freeSpinsSettlementCodec.decode(deserializeMap(round.getFeatureContext()));
    }

    /**
     * Whether a round ended a free-spins feature, judged from the round alone - needed precisely when
     * the round carries no context to say so.
     *
     * <p>A free-spin iteration is one with no bet debit (the trigger already paid), and a respin is
     * excluded by kind because it shares that property. Of those, the one whose <em>after</em> state is
     * the base game is the one that settled: {@code postProcessSpin} returns to {@code BaseGame} only on
     * the branch that credits the feature.
     */
    private static boolean settlesAFreeSpinsFeature(GameRound round) {
        return round.getRoundKind() != RoundKind.RESPIN
                && round.getBetTransactionId() == null
                && round.getStateContext() == GameState.BASE_GAME;
    }

    /**
     * The carry as <em>cells</em>: one entry per position, in the order the engine stamped them.
     *
     * <p>Deduplicated because the carry is a set of wilds, not of squares - two of them can sit on the
     * same cell with different counters left to run, and both stamp the same square. That costs the
     * board nothing, but it would have a page report four carried wilds while marking three, so the
     * flattening happens here rather than being explained away downstream.
     */
    private static List<int[]> distinctCells(List<WildFeatureEngine.WildCell> wilds) {
        Set<String> seen = new LinkedHashSet<>();
        List<int[]> cells = new ArrayList<>();
        for (WildFeatureEngine.WildCell wild : wilds) {
            if (seen.add(wild.row() + ":" + wild.col())) {
                cells.add(new int[] {wild.row(), wild.col()});
            }
        }
        return List.copyOf(cells);
    }

    /**
     * What a round's wild carry was, as far as replaying it is concerned: how the wilds behave, and which
     * ones the round recorded going in. {@link #NONE} covers every round without one, which is most of
     * them - no wild features, expanding-only wilds, or a strip set the behaviours are not live on.
     */
    private record WildCarry(String mode, List<WildFeatureEngine.WildCell> cells) {
        static final WildCarry NONE = new WildCarry(null, List.of());
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
     * The wilds this spin carried in, read back off the round.
     *
     * <p>Expanding wilds need nothing: they are a pure function of the drawn grid, so the transform is
     * simply re-applied and the carry is empty. Sticky and walking wilds are seeded from wilds held over
     * between spins, which live on the <em>session</em> while the feature runs - so the round records its
     * own copy in {@code feature_context} at spin time, exactly as V12 made respins record the coins
     * held going into them, and the replay reads that rather than the session, which has long since
     * moved on.
     *
     * <p>A round played before that capture existed has no copy to read. It is refused up front with a
     * reason rather than replayed as though the carry had been empty: on any spin after the first it was
     * not, and an audit surface should decline to claim a proof it cannot guarantee. Refusing here also
     * keeps a mismatch reaching {@code sequencesMatch} meaning what it should - the engine genuinely
     * diverged.
     */
    private WildCarry carriedWilds(GameRound round, SlotMathDefinition math, ReelStripSet stripSet) {
        WildFeatureConfig wilds = math.wildFeatures();
        if (!wilds.appliesOn(stripSet) || !(wilds.sticky() || wilds.walking())) {
            return WildCarry.NONE;
        }
        String mode = wilds.walking() ? "WALKING" : "STICKY";
        if (round.getFeatureContext() == null || round.getFeatureContext().isBlank()) {
            throw new RgsException(ErrorCode.ROUND_NOT_REPLAYABLE,
                    "Round " + round.getRoundId() + " was played on " + stripSet + " with "
                            + mode.toLowerCase() + " wilds, and predates the capture that records the "
                            + "wilds carried into a spin on the round itself, so the board cannot be "
                            + "rebuilt from this round's draws alone");
        }
        return new WildCarry(mode,
                wildCarryPayloadCodec.decode(deserializeMap(round.getFeatureContext())));
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
