package com.velocity.rgs.slot.service;

import com.velocity.rgs.slot.feature.pickcollect.PickCollectEngine;
import com.velocity.rgs.slot.feature.pickcollect.PickCollectState;
import com.velocity.rgs.slot.math.config.PickCollectConfig;
import com.velocity.rgs.slot.math.config.SlotMathDefinition;
import com.velocity.rgs.slot.math.config.SlotMathLoader;
import com.velocity.rgs.rng.RandomNumberGenerator;
import com.velocity.rgs.rng.RngDrawSink;
import com.velocity.rgs.rng.SecureRandomNumberGenerator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Design aid (not an assertion): measures the standalone average win of the Pick &amp; Collect feature
 * for each catalog game, expressed as a bet multiple. The number feeds the organic-trigger tuning:
 * the feature's contribution to base-game RTP is {@code (1 / triggerOneInN) * E[W]}, so to target a
 * contribution {@code P} (in RTP percentage points) we set {@code triggerOneInN = 100 * E[W] / P}.
 *
 * <p>Pure math, no Spring/Postgres. Tagged {@code slow} so it stays out of the default build. Run with:
 * <pre>{@code mvn -Dtest.excludedGroups= -Dgroups=slow -Dtest=PickCollectFeatureMeasurementTest test}</pre>
 */
@Tag("slow")
class PickCollectFeatureMeasurementTest {

    private static final long PLAYS = 1_000_000L;
    private static final BigDecimal BET = BigDecimal.ONE;

    @ParameterizedTest(name = "{0} feature E[W]")
    @ValueSource(strings = {"aztec-fire", "frost-crown", "inferno-riches"})
    void measureFeatureAverageWin(String gameId) {
        SlotMathDefinition math = new SlotMathLoader().load(gameId, "v1").math();
        PickCollectConfig cfg = math.pickCollect();
        PickCollectEngine engine = new PickCollectEngine();

        BigDecimal total = BigDecimal.ZERO;
        long zeroWins = 0;
        for (long i = 0; i < PLAYS; i++) {
            RandomNumberGenerator rng = new SecureRandomNumberGenerator(RngDrawSink.inMemory());
            PickCollectState state = engine.startFeature(cfg, BET, rng, 0);
            while (state.status() == PickCollectState.Status.ACTIVE) {
                int pos = randomUnopened(state, rng);
                engine.applyPick(state, pos, cfg);
            }
            BigDecimal win = engine.finalizeFeature(state, cfg, "EUR").finalWin().amount();
            total = total.add(win);
            if (win.signum() == 0) {
                zeroWins++;
            }
        }

        BigDecimal avg = total.divide(BigDecimal.valueOf(PLAYS), 4, RoundingMode.HALF_UP);
        double bust = 100.0 * zeroWins / PLAYS;
        System.out.printf("PICK FEATURE [%s]: E[W]=%sx  bust(0-win)=%.2f%%  triggerOneInN(for P=4.0)=%.0f  P@%d=%.3f%n",
                gameId, avg, bust, 100.0 * avg.doubleValue() / 4.0,
                cfg.triggerOneInN(), 100.0 * avg.doubleValue() / cfg.triggerOneInN());
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
}
