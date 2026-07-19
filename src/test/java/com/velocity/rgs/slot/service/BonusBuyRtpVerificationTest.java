package com.velocity.rgs.slot.service;

import com.velocity.rgs.slot.feature.pickcollect.PickCollectEngine;
import com.velocity.rgs.slot.feature.respin.RespinEngine;
import com.velocity.rgs.slot.math.config.SlotMathDefinition;
import com.velocity.rgs.slot.math.config.SlotMathLoader;
import com.velocity.rgs.slot.math.config.SlotMathRegistry;
import com.velocity.rgs.slot.math.engine.GridGenerationEngine;
import com.velocity.rgs.slot.math.engine.ReelEvaluator;
import com.velocity.rgs.slot.math.engine.WildFeatureEngine;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Long-running statistical guard that the purchasable {@code FREE_SPINS_BUY} feature returns its declared
 * {@code targetRtp} - the channel the historical bug lived in (costs were hand-set to arbitrary multiples,
 * so the buy paid 90% on one game and 207% on another). The bought free-spins round is intentionally
 * decoupled from the organic 10-spin trigger: it awards its own (larger) {@code freeSpinsAwarded} so the
 * buy is an industry-priced feature (~80x / ~100x / ~150x by volatility), and its {@code costMultiplier}
 * is calibrated to {@code E[featureWin] / 0.96}. This test is what keeps that contract honest.
 *
 * <p>Tagged {@code slow} (excluded from the default build). Run with:
 * <pre>{@code mvn -Prtp test -Dtest=BonusBuyRtpVerificationTest}</pre>
 *
 * <p>Pure math: drives {@link RtpSimulationService} with hand-wired stateless collaborators - no Spring
 * context, Postgres, or Redis.
 */
@Tag("slow")
class BonusBuyRtpVerificationTest {

    private static final String MATH_VERSION = "v1";

    /**
     * 250,000 bought features.
     *
     * <p>Was 1,000,000, justified only by the assertion that it was "enough to converge the
     * high-volatility Inferno buy" - never measured, unlike {@code BASE_SPINS} in
     * {@link RtpSimulationVerificationTest}. Measuring it showed the horizon was ~4x oversized, and
     * that mattered: at 1M this was the single most expensive test in the repo (833s of a 22-minute
     * guard suite, more than the four 8M-spin base-game simulations combined).
     *
     * <p>Measured per-run sigma over 5 runs at this horizon: 0.073pp (aztec-fire), 0.155pp
     * (frost-crown), 0.126pp (inferno-riches), 0.132pp (jade-tiger). The worst of those leaves 3.9
     * sigma of headroom against the 0.6pp {@link #TOLERANCE} - roughly 0.04% chance of a spurious
     * failure per run across all four games, matching the base-game guard's flake budget and clearing
     * the >3.5 sigma bar {@code BASE_SPINS} was itself sized to.
     *
     * <p>Note the horizon only sharpens discrimination near the tolerance boundary; it does not move
     * the boundary. If you cut it further, re-measure - do not extrapolate past what was sampled.
     *
     * <p>Overridable via {@code -Drtp.buys}; the pom carries the same default and {@code -Psmoke} drops
     * it to a fast pre-commit horizon with a correspondingly wider {@link #TOLERANCE}.
     */
    private static final long BUYS = Long.getLong("rtp.buys", 250_000L);

    /** Acceptable absolute deviation from the declared RTP, in percentage points. */
    private static final BigDecimal TOLERANCE = new BigDecimal(System.getProperty("rtp.tolerance", "0.6"));

    private RtpSimulationService newService(String gameId, SlotMathDefinition math) {
        SlotMathRegistry registry = new SlotMathRegistry(Map.of(gameId + "@" + MATH_VERSION, math));
        return new RtpSimulationService(registry, new GridGenerationEngine(),
                new ReelEvaluator(), new PickCollectEngine(), new RespinEngine(new GridGenerationEngine()), new WildFeatureEngine());
    }

    @ParameterizedTest(name = "{0} bonus-buy free-spins RTP converges to declared target")
    @ValueSource(strings = {"aztec-fire", "frost-crown", "inferno-riches", "jade-tiger"})
    void bonusBuyFreeSpinsRtpConvergesToTarget(String gameId) {
        SlotMathLoader loader = new SlotMathLoader();
        SlotMathDefinition math = loader.load(gameId, MATH_VERSION).math();
        RtpSimulationService service = newService(gameId, math);

        RtpSimulationRequest request = RtpSimulationRequest.builder()
                .gameId(gameId)
                .mathVersion(MATH_VERSION)
                .bet(new BigDecimal("1.00"))
                .spinsBaseGame(0)
                .spinsPowerBet(0)
                .spinsBonusBuyFreeSpins(BUYS)
                .pickStrategy(RtpSimulationRequest.PickStrategy.RANDOM_UNOPENED)
                .build();

        RtpReport report = service.run(request, "buy-rtp-verify-" + gameId);
        BigDecimal buyRtp = report.channels().get("BONUS_BUY_FREE_SPINS").rtpPercent();
        BigDecimal target = math.targetRtp();
        BigDecimal deviation = buyRtp.subtract(target).abs();

        System.out.printf("BUY RTP verification [%s]: target=%s%% simulated=%s%% deviation=%s pp over %,d buys%n",
                gameId, target, buyRtp, deviation, BUYS);

        assertThat(deviation)
                .as("simulated bonus-buy free-spins RTP %s%% must be within %s pp of declared target %s%% for %s",
                        buyRtp, TOLERANCE, target, gameId)
                .isLessThanOrEqualTo(TOLERANCE);
    }
}
