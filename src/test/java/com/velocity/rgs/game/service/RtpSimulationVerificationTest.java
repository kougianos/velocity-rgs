package com.velocity.rgs.game.service;

import com.velocity.rgs.game.feature.pickcollect.PickCollectEngine;
import com.velocity.rgs.math.config.SlotMathDefinition;
import com.velocity.rgs.math.config.SlotMathLoader;
import com.velocity.rgs.math.config.SlotMathRegistry;
import com.velocity.rgs.math.engine.GridGenerationEngine;
import com.velocity.rgs.math.engine.ReelEvaluator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Long-running statistical verification that every game in the catalog returns its declared
 * {@code targetRtp} in the base game. The three shipped games deliberately share identical reel
 * strips but carry distinct, volatility-shaped pay tables (Frost Crown = low volatility / 2,000x cap,
 * Aztec Fire = medium / 10,000x, Inferno Riches = high / 25,000x). The BASE_GAME channel folds in both
 * naturally-triggered free spins and the organically-triggered Pick &amp; Collect feature (~4% RTP
 * each); each pay table is scaled so the combined base-game RTP converges to the same 96% — this test
 * is what guards that contract.
 *
 * <p>Tagged {@code slow} so it is excluded from the default {@code mvn test} / {@code mvn verify}
 * runs (each 1M-spin simulation takes several seconds). Run it explicitly with:
 * <pre>{@code mvn -Dtest.excludedGroups= -Dgroups=slow -Dtest=RtpSimulationVerificationTest test}</pre>
 *
 * <p>This is a pure-math test: it drives {@link RtpSimulationService} with hand-wired stateless
 * collaborators and requires no Spring context, Postgres, or Redis.
 */
@Tag("slow")
class RtpSimulationVerificationTest {

    private static final String MATH_VERSION = "v1";

    /**
     * 2,000,000 base-game spins. Higher than the historical 1M horizon because the high-volatility
     * Inferno Riches pay table (rare 733x premium hits) needs more samples to converge tightly.
     */
    private static final long BASE_SPINS = 2_000_000L;

    /** Acceptable absolute deviation from the declared RTP, in percentage points. */
    private static final BigDecimal TOLERANCE = new BigDecimal("0.6");

    private RtpSimulationService newService(String gameId, SlotMathDefinition math) {
        SlotMathRegistry registry = new SlotMathRegistry(Map.of(gameId + "@" + MATH_VERSION, math));
        return new RtpSimulationService(registry, new GridGenerationEngine(),
                new ReelEvaluator(), new PickCollectEngine());
    }

    @ParameterizedTest(name = "{0} base-game RTP converges to declared target")
    @ValueSource(strings = {"aztec-fire", "frost-crown", "inferno-riches"})
    void baseGameRtpConvergesToDeclaredTarget(String gameId) {
        SlotMathLoader loader = new SlotMathLoader();
        SlotMathDefinition math = loader.load(gameId, MATH_VERSION).math();
        RtpSimulationService service = newService(gameId, math);

        RtpSimulationRequest request = RtpSimulationRequest.builder()
                .gameId(gameId)
                .mathVersion(MATH_VERSION)
                .bet(new BigDecimal("1.00"))
                .spinsBaseGame(BASE_SPINS)
                .spinsBonusBuyFreeSpins(0)
                .pickStrategy(RtpSimulationRequest.PickStrategy.RANDOM_UNOPENED)
                .build();

        RtpReport report = service.run(request, "rtp-verify-" + gameId);
        BigDecimal baseRtp = report.channels().get("BASE_GAME").rtpPercent();
        BigDecimal target = math.targetRtp();
        BigDecimal deviation = baseRtp.subtract(target).abs();

        System.out.printf("RTP verification [%s]: target=%s%% simulated=%s%% deviation=%s pp over %,d spins%n",
                gameId, target, baseRtp, deviation, BASE_SPINS);

        assertThat(deviation)
                .as("simulated base-game RTP %s%% must be within %s pp of declared target %s%% for %s",
                        baseRtp, TOLERANCE, target, gameId)
                .isLessThanOrEqualTo(TOLERANCE);
    }
}
