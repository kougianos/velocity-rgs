package com.velocity.rgs.slot.math.engine;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Outcome of evaluating a round against a math model. {@code totalWin} is already scaled to currency
 * minor units (HALF_UP) and capped by {@code limits.maxWinPerRoundMultiplier}; when the cap is hit a
 * {@code MAX_WIN_CAPPED} reason code is emitted.
 *
 * <p>A round is a <em>sequence</em> of drops, not a single grid: {@link #steps()} carries one
 * {@link CascadeStep} per drop, and {@code totalWin} / {@code winLines} are the flattened aggregate
 * across all of them. Games without cascades simply produce a one-step sequence, so callers never
 * branch on whether a game tumbles - the persistence, replay and client paths all read {@code steps()}
 * uniformly. {@code winLines} payouts are already scaled by each step's progressive multiplier, so the
 * flat list still sums to the round's win.
 */
public record EvaluationResult(BigDecimal totalWin, List<WinLine> winLines, List<String> reasonCodes,
                               List<CascadeStep> steps) {

    public EvaluationResult {
        Objects.requireNonNull(totalWin, "totalWin");
        Objects.requireNonNull(winLines, "winLines");
        Objects.requireNonNull(reasonCodes, "reasonCodes");
        Objects.requireNonNull(steps, "steps");
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("steps must contain at least the round's initial drop");
        }
        winLines = List.copyOf(winLines);
        reasonCodes = List.copyOf(reasonCodes);
        steps = List.copyOf(steps);
    }

    /** True when the round tumbled - i.e. at least one drop paid and was refilled. */
    public boolean cascaded() {
        return steps.size() > 1;
    }
}
