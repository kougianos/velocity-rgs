package com.velocity.rgs.slot.service;

import com.velocity.rgs.slot.math.config.SlotMathDefinition;
import com.velocity.rgs.slot.math.config.SlotMathLoader;
import com.velocity.rgs.slot.math.domain.ReelStripSet;
import com.velocity.rgs.slot.math.domain.SymbolType;
import com.velocity.rgs.slot.math.engine.GridGenerationEngine;
import com.velocity.rgs.slot.math.engine.GridGenerationResult;
import com.velocity.rgs.slot.math.engine.ReelEvaluator;
import com.velocity.rgs.rng.RandomNumberGenerator;
import com.velocity.rgs.rng.RngDrawSink;
import com.velocity.rgs.rng.SecureRandomNumberGenerator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

/**
 * Design aid (not an assertion) for pricing the {@code FREE_SPINS_BUY} bonus buy to industry standards.
 * The bought feature keeps an industry-standard spin count ({@link #BUY_SPINS}, in the 10–15 range) and
 * is made richer per spin via a flat {@code freeSpinsWinMultiplier} (the "you start with higher
 * multipliers" mechanic), so the buy can sit at an industry-standard cost ({@code 80 / 100 / 150x} by
 * volatility) and still return ~96% RTP.
 *
 * <p>For each game it measures the mean {@code BUY_SPINS}-spin free-spins win {@code E[W]} (bet multiples)
 * and prints the exact {@code freeSpinsWinMultiplier = 0.96 x costMultiplier / E[W]} that lands the buy at
 * 96% RTP. Plug that into the JSON {@code bonusBuyOptions[FREE_SPINS_BUY].freeSpinsWinMultiplier} (the cost
 * and the {@code BUY_SPINS}-spin award stay as authored), then guard with {@link BonusBuyRtpVerificationTest}.
 *
 * <p>Pure math, no Spring/Postgres. Tagged {@code slow}. Run with:
 * <pre>{@code mvn -Pcalibrate test -Dtest=BonusBuyCalibrationHarness}</pre>
 */
@Tag("slow")
@Tag("calibration")
class BonusBuyCalibrationHarness {

    private static final String MATH_VERSION = "v1";
    private static final int BUY_SPINS = 12;            // industry-standard 10–15 spin feature
    private static final long PLAYS = Long.getLong("calibrate.plays", 1_000_000L);
    private static final BigDecimal BET = BigDecimal.ONE;
    private static final double TARGET_RTP = 0.96;

    private final GridGenerationEngine gridEngine = new GridGenerationEngine();
    private final ReelEvaluator evaluator = new ReelEvaluator();

    @ParameterizedTest(name = "calibrate buy {0} @ {1}x")
    @CsvSource({
            "frost-crown,80",
            "aztec-fire,100",
            "jade-tiger,125",
            "inferno-riches,150",
    })
    void calibrate(String gameId, int cost) {
        SlotMathDefinition math = new SlotMathLoader().load(gameId, MATH_VERSION).math();

        double eW = meanFreeSpinsWin(math, BUY_SPINS, PLAYS);
        double multiplier = TARGET_RTP * cost / eW;
        double rtpAtRounded = multiplier(Math.round(multiplier * 100.0) / 100.0, eW, cost);

        System.out.printf(
                "BUY-CALIBRATE [%s]: cost=%dx spins=%d E[W]=%.4fx -> freeSpinsWinMultiplier=%.4f "
                        + "(round2=%.2f, RTP@round2=%.2f%%)%n",
                gameId, cost, BUY_SPINS, eW, multiplier,
                Math.round(multiplier * 100.0) / 100.0, rtpAtRounded);
    }

    private double multiplier(double m, double eW, int cost) {
        return m * eW / cost * 100.0;
    }

    /** Mean total free-spins win (bet multiples) for a feature awarding {@code initialSpins} spins. */
    private double meanFreeSpinsWin(SlotMathDefinition math, int initialSpins, long plays) {
        double sum = 0.0;
        int minScatter = math.scatterTriggers().minCount();
        int retrigger = math.scatterTriggers().retriggerAwards();
        for (long i = 0; i < plays; i++) {
            RandomNumberGenerator rng = newRng();
            double total = 0.0;
            int remaining = initialSpins;
            while (remaining > 0) {
                GridGenerationResult grid = gridEngine.generate(math, ReelStripSet.FREE_SPINS, rng);
                total += evaluator.evaluate(grid.matrix(), BET, math).totalWin().doubleValue();
                remaining--;
                if (countScatters(grid.matrix(), math) >= minScatter) {
                    remaining += retrigger;
                }
            }
            sum += total;
        }
        return sum / plays;
    }

    private int countScatters(int[][] matrix, SlotMathDefinition math) {
        int scatterId = math.symbols().stream()
                .filter(s -> s.type() == SymbolType.SCATTER)
                .mapToInt(s -> s.id()).findFirst().orElse(-1);
        int count = 0;
        for (int[] col : matrix) {
            for (int sym : col) {
                if (sym == scatterId) count++;
            }
        }
        return count;
    }

    private RandomNumberGenerator newRng() {
        return new SecureRandomNumberGenerator(RngDrawSink.inMemory());
    }
}
