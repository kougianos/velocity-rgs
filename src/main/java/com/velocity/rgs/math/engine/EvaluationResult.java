package com.velocity.rgs.math.engine;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Outcome of evaluating a 3x5 matrix against a math model. {@code totalWin} is already scaled to currency
 * minor units (HALF_UP) and capped by {@code limits.maxWinPerRoundMultiplier}; when the cap is hit a
 * {@code MAX_WIN_CAPPED} reason code is emitted.
 */
public record EvaluationResult(BigDecimal totalWin, List<WinLine> winLines, List<String> reasonCodes) {

    public EvaluationResult {
        Objects.requireNonNull(totalWin, "totalWin");
        Objects.requireNonNull(winLines, "winLines");
        Objects.requireNonNull(reasonCodes, "reasonCodes");
        winLines = List.copyOf(winLines);
        reasonCodes = List.copyOf(reasonCodes);
    }
}
