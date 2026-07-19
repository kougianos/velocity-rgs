package com.velocity.rgs.slot.math.config;

import java.math.BigDecimal;
import java.util.List;

/**
 * Cascading (tumbling) reels: winning symbols are removed, the survivors drop, fresh symbols refill from
 * the top, and the new grid is evaluated again. The round ends when a drop pays nothing, or when
 * {@code maxCascades} refills have happened.
 *
 * <p>The {@code math.cascades} block of {@code games/<gameId>/<mathVersion>.json}. Absent means
 * {@link #disabled()} - every game authored before cascades existed keeps its single-drop behaviour and
 * its calibrated RTP untouched.
 *
 * @param enabled         whether the game cascades at all
 * @param maxCascades     hard bound on <em>refills</em> per round, so a pathological chain cannot loop
 *                        forever; a round therefore has at most {@code maxCascades + 1} steps
 * @param stepMultipliers the progressive multiplier ladder, indexed by step. {@code stepMultipliers[0]}
 *                        applies to the initial drop, {@code [1]} to the first cascade, and so on; the
 *                        last entry repeats for any step beyond the list. This is the mechanic's whole
 *                        draw - a chain that keeps paying is worth progressively more - so it is config,
 *                        not code.
 */
public record CascadeConfig(
        boolean enabled,
        int maxCascades,
        List<BigDecimal> stepMultipliers
) {

    /** A game that does not cascade: one drop, no multiplier ladder. */
    public static CascadeConfig disabled() {
        return new CascadeConfig(false, 0, List.of(BigDecimal.ONE));
    }

    public CascadeConfig {
        stepMultipliers = stepMultipliers == null || stepMultipliers.isEmpty()
                ? List.of(BigDecimal.ONE)
                : List.copyOf(stepMultipliers);
        if (enabled) {
            if (maxCascades < 1) {
                throw new IllegalArgumentException(
                        "cascades.maxCascades must be >= 1 when cascades are enabled, found " + maxCascades);
            }
            for (BigDecimal m : stepMultipliers) {
                if (m == null || m.signum() <= 0) {
                    throw new IllegalArgumentException(
                            "cascades.stepMultipliers entries must be > 0, found " + m);
                }
            }
        }
    }

    /**
     * The multiplier for step {@code stepIndex} (0 = the initial drop). Steps beyond the configured
     * ladder repeat its final entry, which is the conventional shape: the multiplier climbs and then
     * holds, rather than growing without bound on a long chain.
     */
    public BigDecimal multiplierFor(int stepIndex) {
        if (stepIndex < 0) {
            throw new IllegalArgumentException("stepIndex must be >= 0, found " + stepIndex);
        }
        int index = Math.min(stepIndex, stepMultipliers.size() - 1);
        return stepMultipliers.get(index);
    }
}
