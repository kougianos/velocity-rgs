package com.velocity.rgs.roulette.engine;

import com.velocity.rgs.roulette.config.RouletteMathDefinition;
import com.velocity.rgs.roulette.domain.RouletteBetKind;
import com.velocity.rgs.rng.RngDrawSink;
import com.velocity.rgs.rng.SecureRandomNumberGenerator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RTP guarantees for European roulette. Unlike slots, the return is mathematically exact (no calibration):
 * every bet pays {@code (covered / pockets) × (payout + 1) = 36/37 = 97.30%}. The deterministic full-cycle
 * checks prove this exactly; the tagged simulation exercises the live wheel + RNG path end to end.
 */
class RouletteRtpVerificationTest {

    /** 36/37 to 10 dp — the house-edge invariant of a European single-zero wheel. */
    private static final BigDecimal HOUSE_EDGE_RTP =
            BigDecimal.valueOf(36).divide(BigDecimal.valueOf(37), 10, RoundingMode.HALF_UP);

    private final RouletteEvaluator evaluator = new RouletteEvaluator();
    private final RouletteMathDefinition math = RouletteFixtures.european();

    @Test
    void everyBetKindReturnsHouseEdgeOverAFullWheelCycle() {
        BigDecimal stake = new BigDecimal("1.00");
        for (RouletteBetKind kind : RouletteBetKind.values()) {
            BigDecimal totalReturn = BigDecimal.ZERO;
            // A fixed bet (straight always on the same number) swept against every pocket exactly once —
            // the empirical RTP of one full cycle. The straight wins on exactly one of the 37 pockets.
            Integer number = kind.requiresNumber() ? 7 : null;
            for (int n = 0; n < math.pocketCount(); n++) {
                totalReturn = totalReturn.add(
                        evaluator.evaluate(n, List.of(new RouletteBet(kind, number, stake)), math).totalWin());
            }
            // Each spin staked 1.00, so total staked = pocketCount.
            BigDecimal rtp = totalReturn.divide(
                    stake.multiply(BigDecimal.valueOf(math.pocketCount())), 10, RoundingMode.HALF_UP);
            assertThat(rtp)
                    .as("RTP for %s", kind)
                    .isEqualByComparingTo(HOUSE_EDGE_RTP);
        }
    }

    @Test
    void theoreticalRtpMatchesHouseEdgeForEveryBetKind() {
        for (RouletteBetKind kind : RouletteBetKind.values()) {
            assertThat(RouletteEvaluator.theoreticalRtp(kind, math))
                    .as("theoretical RTP for %s", kind)
                    .isEqualByComparingTo(HOUSE_EDGE_RTP);
        }
    }

    @Test
    @Tag("slow")
    void liveWheelSimulationConvergesToHouseEdge() {
        RouletteWheel wheel = new RouletteWheel();
        SecureRandomNumberGenerator rng = new SecureRandomNumberGenerator(RngDrawSink.inMemory());
        long spins = 2_000_000L;
        BigDecimal stake = new BigDecimal("1.00");
        BigDecimal totalWin = BigDecimal.ZERO;
        for (long i = 0; i < spins; i++) {
            int n = wheel.spin(math, rng).number();
            totalWin = totalWin.add(
                    evaluator.evaluate(n, List.of(new RouletteBet(RouletteBetKind.RED, null, stake)), math)
                            .totalWin());
        }
        double rtp = totalWin.doubleValue() / (spins * 1.0); // stake is 1.00 per spin
        // RED has low per-spin variance (sd≈1.0); over 2M spins 3·SE ≈ 0.2pp, so 0.5pp is a safe guard.
        assertThat(rtp).isBetween(0.9680, 0.9780);
    }
}
