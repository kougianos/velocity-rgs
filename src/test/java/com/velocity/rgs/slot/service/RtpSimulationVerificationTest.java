package com.velocity.rgs.slot.service;

import com.velocity.rgs.slot.feature.pickcollect.PickCollectEngine;
import com.velocity.rgs.slot.math.config.SlotMathDefinition;
import com.velocity.rgs.slot.math.config.SlotMathLoader;
import com.velocity.rgs.slot.math.config.SlotMathRegistry;
import com.velocity.rgs.slot.math.engine.GridGenerationEngine;
import com.velocity.rgs.slot.math.engine.ReelEvaluator;
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
 * each); each pay table is scaled so the combined base-game RTP converges to the same 96% - this test
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
     * 8,000,000 base-game spins.
     *
     * <p>Sized from measurement, not intuition. The simulation is unseeded ({@link
     * com.velocity.rgs.rng.SecureRandomNumberGenerator}), so each run is a fresh sample and this test
     * either flakes or it does not depending purely on how {@link #TOLERANCE} compares to the spread.
     * Six runs at the historical 2M horizon measured a per-run standard deviation of ~0.24pp
     * (aztec-fire), ~0.22pp (frost-crown) and ~0.28pp (inferno-riches) - against a 0.6pp tolerance
     * that is only ~1.8-2.0 sigma of headroom, i.e. roughly a 10% chance that at least one game
     * spuriously fails on any given run. That is far too flaky for a CI guard: a job that cries wolf
     * every other week gets ignored, which is worse than no job.
     *
     * <p>Sampling error scales as 1/sqrt(n), so 4x the spins halves sigma to ~0.11-0.14pp, giving
     * >3.5 sigma of headroom and a spurious-failure rate near 0.03% per run. Measured cost of the
     * change: this test went 82s -> 272s (sublinear in spins - JIT warmup amortises), taking the
     * full {@code -Prtp} guard suite to ~9 min. Free on a public repo.
     *
     * <p>If you tighten {@link #TOLERANCE}, re-measure the spread first - do not guess.
     */
    private static final long BASE_SPINS = 8_000_000L;

    /**
     * Acceptable absolute deviation from the declared RTP, in percentage points. See {@link #BASE_SPINS}
     * for why this value and the spin count have to be chosen together.
     */
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
