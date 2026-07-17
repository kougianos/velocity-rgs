package com.velocity.rgs.slot.math.engine;

import com.velocity.rgs.slot.math.config.SlotMathDefinition;
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
 */
@Component
public class ReelEvaluator {

    private final Map<WinModel, WinEvaluator> byModel = new EnumMap<>(WinModel.class);

    @Autowired
    public ReelEvaluator(List<WinEvaluator> evaluators) {
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
     * Wires the built-in evaluators directly. Both implementations are stateless with no dependencies,
     * so this stays a faithful stand-in for the Spring-wired instance - it exists so the pure-math tests
     * and calibration harnesses can keep constructing an evaluator without a container.
     */
    public ReelEvaluator() {
        this(List.of(new PaylineWinEvaluator(), new WaysWinEvaluator()));
    }

    public EvaluationResult evaluate(int[][] matrix, BigDecimal bet, SlotMathDefinition math) {
        WinEvaluator evaluator = byModel.get(math.winModel());
        if (evaluator == null) {
            throw new IllegalStateException("no WinEvaluator for " + math.winModel()
                    + " (game " + math.gameId() + ")");
        }
        return evaluator.evaluate(matrix, bet, math);
    }
}
