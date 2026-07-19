package com.velocity.rgs.slot.math.engine;

import com.velocity.rgs.rng.RandomNumberGenerator;
import com.velocity.rgs.slot.math.config.SlotMathDefinition;
import com.velocity.rgs.slot.math.domain.ReelStripSet;
import com.velocity.rgs.slot.math.domain.WinModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Evaluates a generated grid into wins, dispatching to the {@link WinEvaluator} for the game's
 * {@link WinModel}.
 *
 * <p>This is the type the engine, replay, and simulation paths depend on, and it is deliberately the
 * only one that knows more than one model exists. Adding a mechanic therefore means adding a
 * {@link WinEvaluator} and a game JSON - callers never change.
 *
 * <p>It is also where a round's <em>shape</em> is decided: {@link #evaluateRound} plays a cascading game
 * out through {@link CascadeEngine} and a non-cascading one in a single drop, returning the same
 * {@link EvaluationResult} either way. Callers hand over an RNG and get back a step sequence; they never
 * branch on the mechanic.
 */
@Component
public class ReelEvaluator {

    private final Map<WinModel, WinEvaluator> byModel = new EnumMap<>(WinModel.class);
    private final CascadeEngine cascadeEngine;

    @Autowired
    public ReelEvaluator(List<WinEvaluator> evaluators, CascadeEngine cascadeEngine) {
        this.cascadeEngine = cascadeEngine;
        for (WinEvaluator e : evaluators) {
            WinEvaluator clash = byModel.put(e.model(), e);
            if (clash != null) {
                throw new IllegalStateException("two evaluators claim " + e.model() + ": "
                        + clash.getClass().getName() + " and " + e.getClass().getName());
            }
        }
        for (WinModel model : WinModel.values()) {
            if (!byModel.containsKey(model)) {
                throw new IllegalStateException("no WinEvaluator registered for " + model);
            }
        }
    }

    /**
     * Wires the built-in evaluators directly. All implementations are stateless with no dependencies
     * beyond the grid engine, so this stays a faithful stand-in for the Spring-wired instance - it
     * exists so the pure-math tests and calibration harnesses can keep constructing an evaluator without
     * a container.
     */
    public ReelEvaluator() {
        this(List.of(new PaylineWinEvaluator(), new WaysWinEvaluator()),
                new CascadeEngine(new GridGenerationEngine()));
    }

    /**
     * Evaluates a single drop. Cascading games need {@link #evaluateRound} instead - this returns only
     * what the given board pays, which for a tumbling game is the first step of the round, not the
     * round.
     */
    public EvaluationResult evaluate(int[][] matrix, BigDecimal bet, SlotMathDefinition math) {
        return evaluatorFor(math).evaluate(matrix, bet, math);
    }

    /**
     * Plays a whole round from its opening board: one drop for a conventional game, or the full tumble
     * for a cascading one.
     *
     * @param stopPositions the reel stops that produced {@code matrix}, recorded on the round's step 0
     * @param stripSet      which strip variant refills draw from - the same one the opening board used
     * @param rng           the round's RNG; every refill draws from it so the round stays replayable
     */
    public EvaluationResult evaluateRound(int[][] matrix, int[] stopPositions, BigDecimal bet,
                                          SlotMathDefinition math, ReelStripSet stripSet,
                                          RandomNumberGenerator rng) {
        WinEvaluator evaluator = evaluatorFor(math);
        if (!math.cascades().enabled()) {
            EvaluationResult single = evaluator.evaluate(matrix, bet, math);
            return withStops(single, stopPositions);
        }
        return cascadeEngine.run(matrix, stopPositions, bet, math, stripSet, evaluator, rng);
    }

    private WinEvaluator evaluatorFor(SlotMathDefinition math) {
        WinEvaluator evaluator = byModel.get(math.winModel());
        if (evaluator == null) {
            throw new IllegalStateException("no WinEvaluator for " + math.winModel()
                    + " (game " + math.gameId() + ")");
        }
        return evaluator;
    }

    /**
     * Stitches the reel stops onto a non-cascading round's single step. The evaluator is handed a grid
     * with no record of how it was drawn, so it leaves the stops empty (see
     * {@link EvaluationSupport#capped}); this is the layer that does know them.
     */
    private static EvaluationResult withStops(EvaluationResult result, int[] stopPositions) {
        CascadeStep step = result.steps().get(0);
        return new EvaluationResult(result.totalWin(), result.winLines(), result.reasonCodes(),
                List.of(new CascadeStep(step.index(), step.grid(), stopPositions, step.winLines(),
                        step.multiplier(), step.stepWin(), step.clearedPositions())));
    }
}
