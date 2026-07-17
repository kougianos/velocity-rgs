package com.velocity.rgs.slot.math.engine;

import com.velocity.rgs.slot.math.config.SlotMathDefinition;
import com.velocity.rgs.slot.math.domain.WinModel;

import java.math.BigDecimal;

/**
 * Turns a generated grid into wins under one {@link WinModel}. Implementations are stateless and must be
 * safe to share across rounds and threads.
 *
 * <p>Games select their model in config rather than in code, so adding a mechanic means adding an
 * implementation here plus a game JSON - never a change to {@link com.velocity.rgs.slot.service.SlotEngineService}
 * or the replay/simulation paths, which resolve the model through {@link ReelEvaluator}.
 */
public interface WinEvaluator {

    /** The model this implementation handles. Exactly one implementation may claim a given model. */
    WinModel model();

    /**
     * Evaluates {@code matrix} for {@code bet}. The returned total is capped at
     * {@code bet * limits.maxWinPerRoundMultiplier}, emitting {@code MAX_WIN_CAPPED} when it truncates.
     *
     * @param matrix indexed {@code [row][reel]}, sized to {@code math.grid()}
     */
    EvaluationResult evaluate(int[][] matrix, BigDecimal bet, SlotMathDefinition math);
}
