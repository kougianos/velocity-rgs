package com.velocity.rgs.slot.service;

import com.velocity.rgs.slot.feature.pickcollect.PickCollectEngine;
import com.velocity.rgs.slot.feature.pickcollect.PickCollectState;
import com.velocity.rgs.slot.math.config.SlotMathDefinition;
import com.velocity.rgs.slot.math.config.SlotMathLoader;
import com.velocity.rgs.slot.math.domain.ReelStripSet;
import com.velocity.rgs.slot.math.domain.SymbolType;
import com.velocity.rgs.slot.math.engine.EvaluationResult;
import com.velocity.rgs.slot.math.engine.GridGenerationEngine;
import com.velocity.rgs.slot.math.engine.GridGenerationResult;
import com.velocity.rgs.slot.math.engine.ReelEvaluator;
import com.velocity.rgs.rng.RandomNumberGenerator;
import com.velocity.rgs.rng.RngDrawSink;
import com.velocity.rgs.rng.SecureRandomNumberGenerator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Design aid (not an assertion): for a freshly re-shaped game it measures the line+free-spins RTP
 * ({@code L}) with the <em>current</em> pay table and the standalone Pick &amp; Collect contribution
 * ({@code P}), using the real engine collaborators. The pay-table scale needed to land total base RTP
 * on the 96% target is then {@code s = (96 - P) / L}. Feed that {@code s} back into the JSON generator
 * ({@code .rgsgen_assemble.py}) and re-verify with {@link RtpSimulationVerificationTest}.
 *
 * <p>Pure math, no Spring/Postgres. Tagged {@code slow} so it stays out of the default build. Run with:
 * <pre>{@code mvn -Prtp test -Dtest=GameRtpCalibrationHarness}</pre>
 */
@Tag("slow")
class GameRtpCalibrationHarness {

    private static final String MATH_VERSION = "v1";
    private static final long LINE_FS_SPINS = 3_000_000L;
    private static final long PICK_PLAYS = 1_000_000L;
    private static final BigDecimal BET = BigDecimal.ONE;
    private static final BigDecimal TARGET = new BigDecimal("96.0");

    private final GridGenerationEngine gridEngine = new GridGenerationEngine();
    private final ReelEvaluator evaluator = new ReelEvaluator();
    private final PickCollectEngine pickEngine = new PickCollectEngine();

    @ParameterizedTest(name = "calibrate {0}")
    @ValueSource(strings = {"frost-crown", "aztec-fire", "inferno-riches"})
    void calibrate(String gameId) {
        SlotMathDefinition math = new SlotMathLoader().load(gameId, MATH_VERSION).math();

        Stats st = measureLineFs(math);
        double l = st.meanPct;
        Pick pick = measurePick(math);
        double p = pick.contributionPct;
        double s = (TARGET.doubleValue() - p) / l;
        // Per-spin Pick & Collect payout (in bet multiples): pays its feature win with prob q=1/triggerOneInN,
        // else 0. Its variance folds into the BASE channel alongside line+FS and is a major contributor.
        double q = 1.0 / math.pickCollect().triggerOneInN();
        double pickVar = q * pick.eW2 - (q * pick.eW) * (q * pick.eW);
        double sigmaTotal = Math.sqrt(st.sigmaSpin * st.sigmaSpin + pickVar);
        double seLineFs = st.sigmaSpin / Math.sqrt(2_000_000d) * 100.0;
        double seTotal = sigmaTotal / Math.sqrt(2_000_000d) * 100.0;

        System.out.printf(
                "CALIBRATE [%s]: L=%.4f%% P=%.4f%% (E[W]=%.2fx cap=%d) lineOnly=%.4f%% fsShare=%.1f%% | "
                        + "sigmaLineFs=%.2fx sigmaTotal=%.2fx SE@2M lineFs=%.3f total=%.3fpp | predicted=%.4f%% -> s=%.4f%n",
                gameId, l, p, pick.eW, math.pickCollect().maxFeatureWinMultiplier(),
                st.lineOnlyPct, 100.0 * (l - st.lineOnlyPct) / l,
                st.sigmaSpin, sigmaTotal, seLineFs, seTotal, l + p, s);
    }

    private static final class Stats {
        double meanPct;       // line+FS RTP %
        double lineOnlyPct;   // line-only RTP %
        double sigmaSpin;     // std dev of per-spin (line+FS) payout in bet multiples
    }

    /** Base-game line wins plus naturally-triggered free-spin wins (Pick &amp; Collect excluded), with variance. */
    private Stats measureLineFs(SlotMathDefinition math) {
        double sum = 0.0, sumSq = 0.0, lineSum = 0.0;
        int minScatter = math.scatterTriggers().minCount();
        for (long i = 0; i < LINE_FS_SPINS; i++) {
            RandomNumberGenerator rng = newRng();
            GridGenerationResult grid = gridEngine.generate(math, ReelStripSet.BASE, rng);
            double spin = evaluator.evaluate(grid.matrix(), BET, math).totalWin().doubleValue();
            lineSum += spin;
            if (countScatters(grid.matrix(), math) >= minScatter) {
                spin += simulateFreeSpins(math, rng).doubleValue();
            }
            sum += spin;
            sumSq += spin * spin;
        }
        double n = LINE_FS_SPINS;
        double mean = sum / n;
        Stats st = new Stats();
        st.meanPct = mean * 100.0;
        st.lineOnlyPct = lineSum / n * 100.0;
        st.sigmaSpin = Math.sqrt(Math.max(0.0, sumSq / n - mean * mean));
        return st;
    }

    private static final class Pick {
        double eW;             // mean feature win (bet multiples)
        double eW2;            // mean squared feature win
        double contributionPct;
    }

    private Pick measurePick(SlotMathDefinition math) {
        double sum = 0.0, sumSq = 0.0;
        for (long i = 0; i < PICK_PLAYS; i++) {
            RandomNumberGenerator rng = newRng();
            PickCollectState st = pickEngine.startFeature(math.pickCollect(), BET, rng, 0);
            while (st.status() == PickCollectState.Status.ACTIVE) {
                pickEngine.applyPick(st, randomUnopened(st, rng), math.pickCollect());
            }
            double w = pickEngine.finalizeFeature(st, math.pickCollect(), "EUR").finalWin().amount().doubleValue();
            sum += w;
            sumSq += w * w;
        }
        Pick pk = new Pick();
        pk.eW = sum / PICK_PLAYS;
        pk.eW2 = sumSq / PICK_PLAYS;
        pk.contributionPct = 100.0 * pk.eW / math.pickCollect().triggerOneInN();
        return pk;
    }

    private BigDecimal simulateFreeSpins(SlotMathDefinition math, RandomNumberGenerator rng) {
        BigDecimal total = BigDecimal.ZERO;
        int remaining = math.scatterTriggers().freeSpinsAwarded();
        int minScatter = math.scatterTriggers().minCount();
        while (remaining > 0) {
            GridGenerationResult grid = gridEngine.generate(math, ReelStripSet.FREE_SPINS, rng);
            total = total.add(evaluator.evaluate(grid.matrix(), BET, math).totalWin());
            remaining--;
            if (countScatters(grid.matrix(), math) >= minScatter) {
                remaining += math.scatterTriggers().retriggerAwards();
            }
        }
        return total;
    }

    private int countScatters(int[][] matrix, SlotMathDefinition math) {
        int scatterId = math.symbols().stream()
                .filter(s -> s.type() == SymbolType.SCATTER)
                .mapToInt(s -> s.id()).findFirst().orElse(-1);
        int count = 0;
        for (int[] row : matrix) {
            for (int sym : row) {
                if (sym == scatterId) count++;
            }
        }
        return count;
    }

    private int randomUnopened(PickCollectState state, RandomNumberGenerator rng) {
        int boardSize = state.boardSize();
        for (int attempt = 0; attempt < boardSize * 4; attempt++) {
            int candidate = rng.nextIndex(boardSize);
            if (!state.openedPositions().contains(candidate)) {
                return candidate;
            }
        }
        for (int i = 0; i < boardSize; i++) {
            if (!state.openedPositions().contains(i)) {
                return i;
            }
        }
        throw new IllegalStateException("No unopened pick positions remain");
    }

    private RandomNumberGenerator newRng() {
        return new SecureRandomNumberGenerator(RngDrawSink.inMemory());
    }
}
