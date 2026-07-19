package com.velocity.rgs.slot.service;

import com.velocity.rgs.catalog.GameInfo;
import com.velocity.rgs.slot.feature.pickcollect.PickCollectEngine;
import com.velocity.rgs.slot.feature.respin.RespinEngine;
import com.velocity.rgs.slot.math.config.GameDefinition;
import com.velocity.rgs.slot.math.config.SlotMathDefinition;
import com.velocity.rgs.slot.math.config.SlotMathLoader;
import com.velocity.rgs.slot.math.config.SlotMathRegistry;
import com.velocity.rgs.slot.math.engine.CascadeEngine;
import com.velocity.rgs.slot.math.engine.GridGenerationEngine;
import com.velocity.rgs.slot.math.engine.PaylineWinEvaluator;
import com.velocity.rgs.slot.math.engine.ReelEvaluator;
import com.velocity.rgs.slot.math.engine.WaysWinEvaluator;
import com.velocity.rgs.slot.math.engine.WildFeatureEngine;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the two shape statistics a slot's spec sheet shows the player, which RTP convergence alone
 * does not constrain at all.
 *
 * <h2>Hit frequency</h2>
 *
 * Every shipped game's {@code presentation.info} declares a "Hit Frequency" row - a number the player
 * reads before staking anything, and which until now nothing computed. This test parses that declared
 * percentage straight out of the game JSON and asserts the simulated base game actually delivers it. If
 * a pay table or reel strip changes in a way that alters how often the game pays, this fails and the
 * declared number has to be re-measured rather than quietly becoming a lie.
 *
 * <p>The measured quantity is the share of <em>rounds</em> that return anything at all, features
 * included - which is what a player means by "how often does this pay". A cascading game counts one
 * round however many times it tumbles.
 *
 * <h2>Max win</h2>
 *
 * {@code limits.maxWinPerRoundMultiplier} is the ceiling the spec sheet advertises as "Max multiplier".
 * No sampled round may exceed it. That is an invariant rather than a statistic, so it is asserted on
 * every game regardless of horizon.
 *
 * <p>Tagged {@code slow}: it shares the {@code -Prtp} guard suite's budget and runs alongside
 * {@link RtpSimulationVerificationTest}, whose {@code BASE_SPINS} discussion explains why these
 * horizons are chosen by measurement rather than intuition.
 */
@Tag("slow")
class GameStatisticsVerificationTest {

    private static final String MATH_VERSION = "v1";

    /**
     * 2,000,000 base-game rounds.
     *
     * <p>A proportion converges far faster than a mean of a heavy-tailed payout: the standard error of a
     * hit rate near 30% is {@code sqrt(p(1-p)/n)}, i.e. ~0.03pp at this horizon, so the 1.0pp tolerance
     * below is enormous headroom (>30 sigma) and the test cannot flake. It is deliberately loose in
     * <em>absolute</em> terms so the declared marketing number is allowed to be a sane round figure
     * rather than a 4-decimal simulation artefact.
     */
    private static final long SPINS = Long.getLong("stats.spins", 2_000_000L);

    /** Acceptable absolute deviation from the declared hit frequency, in percentage points. */
    private static final BigDecimal TOLERANCE = new BigDecimal(System.getProperty("stats.tolerance", "1.0"));

    /** Leading percentage of a spec value like {@code "27.40% - for RTP 96.00%"}. */
    private static final Pattern DECLARED_PERCENT = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*%");

    @ParameterizedTest(name = "{0} delivers its declared hit frequency and respects its win cap")
    @ValueSource(strings = {"aztec-fire", "frost-crown", "inferno-riches", "jade-tiger",
            "gilded-cascade", "dragon-hoard"})
    void declaredStatisticsHold(String gameId) {
        GameDefinition game = new SlotMathLoader().load(gameId, MATH_VERSION);
        SlotMathDefinition math = game.math();
        RtpReport.Channel base = simulate(gameId, math).channels().get("BASE_GAME");

        System.out.printf("STATS [%s]: hitFrequency=%s%% maxWin=%sx (cap %dx) rtp=%s%% over %,d rounds%n",
                gameId, base.hitFrequencyPercent(), base.maxWinMultiplier(),
                math.limits().maxWinPerRoundMultiplier(), base.rtpPercent(), SPINS);
        base.winDistribution().forEach(b ->
                System.out.printf("   %-11s %,12d  %s%%%n", b.label(), b.count(), b.sharePercent()));

        BigDecimal declared = declaredHitFrequency(game)
                .orElseThrow(() -> new AssertionError(
                        "game " + gameId + " declares no parseable 'Hit Frequency' spec row; every game "
                                + "shows one to the player, so add it to presentation.info.specs"));

        assertThat(base.hitFrequencyPercent().subtract(declared).abs())
                .as("simulated hit frequency %s%% must be within %s pp of the %s%% declared on %s's "
                                + "spec sheet", base.hitFrequencyPercent(), TOLERANCE, declared, gameId)
                .isLessThanOrEqualTo(TOLERANCE);

        assertThat(base.maxWinMultiplier())
                .as("no round may pay past %s's declared %dx ceiling",
                        gameId, math.limits().maxWinPerRoundMultiplier())
                .isLessThanOrEqualTo(BigDecimal.valueOf(math.limits().maxWinPerRoundMultiplier()));

        assertThat(base.hits())
                .as("the zero band's complement must equal the hit count for %s", gameId)
                .isEqualTo(base.spins() - base.winDistribution().get(0).count());
    }

    /** The percentage on the game's player-visible "Hit Frequency" spec row, if it declares one. */
    private static Optional<BigDecimal> declaredHitFrequency(GameDefinition game) {
        if (game.presentation().info() == null) {
            return Optional.empty();
        }
        for (GameInfo.InfoSpec spec : game.presentation().info().specs()) {
            if (!"Hit Frequency".equalsIgnoreCase(spec.label()) || spec.values().isEmpty()) {
                continue;
            }
            Matcher matcher = DECLARED_PERCENT.matcher(spec.values().get(0));
            if (matcher.find()) {
                return Optional.of(new BigDecimal(matcher.group(1)));
            }
        }
        return Optional.empty();
    }

    private static RtpReport simulate(String gameId, SlotMathDefinition math) {
        RtpSimulationService service = new RtpSimulationService(
                new SlotMathRegistry(Map.of(gameId + "@" + MATH_VERSION, math)),
                new GridGenerationEngine(),
                new ReelEvaluator(List.of(new PaylineWinEvaluator(), new WaysWinEvaluator()),
                        new CascadeEngine(new GridGenerationEngine())),
                new PickCollectEngine(), new RespinEngine(new GridGenerationEngine()), new WildFeatureEngine());
        return service.run(RtpSimulationRequest.builder()
                .gameId(gameId)
                .mathVersion(MATH_VERSION)
                .bet(new BigDecimal("1.00"))
                .spinsBaseGame(SPINS)
                .spinsBonusBuyFreeSpins(0)
                .pickStrategy(RtpSimulationRequest.PickStrategy.RANDOM_UNOPENED)
                .build(), "stats-" + gameId);
    }
}
