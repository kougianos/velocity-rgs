package com.velocity.rgs.game.service;

import com.velocity.rgs.game.feature.pickcollect.PickCollectEngine;
import com.velocity.rgs.math.config.SlotMathDefinition;
import com.velocity.rgs.math.config.SlotMathLoader;
import com.velocity.rgs.math.config.SlotMathRegistry;
import com.velocity.rgs.math.engine.GridGenerationEngine;
import com.velocity.rgs.math.engine.ReelEvaluator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Long-running statistical verification that the simulated base-game RTP converges to the
 * {@code targetRtp} declared inside the math definition ({@code math/aztec-fire/v1.json}).
 *
 * <p>Tagged {@code slow} so it is excluded from the default {@code mvn test} / {@code mvn verify}
 * runs (a 1M-spin simulation takes several seconds to a minute). Run it explicitly with:
 * <pre>{@code mvn -Prtp test}</pre>
 * or
 * <pre>{@code mvn -Dtest.excludedGroups= -Dtest.groups=slow test}</pre>
 *
 * <p>This is a pure-math test: it drives {@link RtpSimulationService} with hand-wired stateless
 * collaborators and requires no Spring context, Postgres, or Redis.
 */
@Tag("slow")
class RtpSimulationVerificationTest {

    private static final String GAME_ID = "aztec-fire";
    private static final String MATH_VERSION = "v1";

    /** 1,000,000 base-game spins (the agreed convergence horizon). */
    private static final long BASE_SPINS = 1_000_000L;

    /** Acceptable absolute deviation from the declared RTP, in percentage points. */
    private static final BigDecimal TOLERANCE = new BigDecimal("0.5");

    private RtpSimulationService newService(SlotMathDefinition math) {
        SlotMathRegistry registry = new SlotMathRegistry(Map.of(GAME_ID + "@" + MATH_VERSION, math));
        return new RtpSimulationService(registry, new GridGenerationEngine(),
                new ReelEvaluator(), new PickCollectEngine());
    }

    @Test
    void baseGameRtpConvergesToDeclaredTarget() {
        SlotMathLoader loader = new SlotMathLoader();
        SlotMathDefinition math = loader.load(GAME_ID, MATH_VERSION);
        RtpSimulationService service = newService(math);

        RtpSimulationRequest request = RtpSimulationRequest.builder()
                .gameId(GAME_ID)
                .mathVersion(MATH_VERSION)
                .bet(new BigDecimal("1.00"))
                .spinsBaseGame(BASE_SPINS)
                .spinsBonusBuyFreeSpins(0)
                .spinsBonusBuyPickCollect(0)
                .pickStrategy(RtpSimulationRequest.PickStrategy.RANDOM_UNOPENED)
                .build();

        RtpReport report = service.run(request, "rtp-verify");
        BigDecimal baseRtp = report.channels().get("BASE_GAME").rtpPercent();
        BigDecimal target = math.targetRtp();
        BigDecimal deviation = baseRtp.subtract(target).abs();

        System.out.printf("RTP verification: target=%s%% simulated=%s%% deviation=%s pp over %,d spins%n",
                target, baseRtp, deviation, BASE_SPINS);

        assertThat(deviation)
                .as("simulated base-game RTP %s%% must be within %s pp of declared target %s%%",
                        baseRtp, TOLERANCE, target)
                .isLessThanOrEqualTo(TOLERANCE);
    }
}
