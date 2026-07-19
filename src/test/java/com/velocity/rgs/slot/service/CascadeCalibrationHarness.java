package com.velocity.rgs.slot.service;

import com.velocity.rgs.slot.feature.pickcollect.PickCollectEngine;
import com.velocity.rgs.slot.feature.respin.RespinEngine;
import com.velocity.rgs.slot.math.config.PickCollectConfig;
import com.velocity.rgs.slot.math.config.RespinConfig;
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

/**
 * Design aid (not an assertion) for games whose RTP cannot be reasoned about a drop at a time -
 * cascades, respins, and anything else where one spin resolves into a variable number of paying events.
 *
 * <p>Prints the measured base-game RTP with the <em>current</em> pay table and the scale {@code s} that
 * lands it on the declared target. Multiply every coefficient in the game's {@code payTable} by
 * {@code s} and re-verify with {@link RtpSimulationVerificationTest}.
 *
 * <p>The scale is very nearly linear even with cascades, which is why one or two passes suffice:
 * scaling every coefficient does not change <em>which</em> drops win, only what they pay, so the chain
 * length distribution - the part that is genuinely hard to reason about - is untouched. It is not
 * exactly linear, because per-line rounding to currency scale and the round win cap both bite at the
 * margins; expect to iterate once.
 *
 * <p>Also prints hit frequency and the max observed win multiplier, which are the numbers a game's
 * player-facing spec sheet declares and {@link GameStatisticsVerificationTest} then guards.
 *
 * <p>Pure math, no Spring/Postgres. Run with:
 * <pre>{@code mvn -Pcalibrate test -Dtest=CascadeCalibrationHarness}</pre>
 */
@Tag("slow")
@Tag("calibration")
class CascadeCalibrationHarness {

    private static final String MATH_VERSION = "v1";
    private static final long SPINS = Long.getLong("calibrate.spins", 2_000_000L);
    private static final BigDecimal BET = BigDecimal.ONE;

    @ParameterizedTest(name = "calibrate {0}")
    @ValueSource(strings = {"gilded-cascade", "dragon-hoard"})
    void calibrate(String gameId) {
        SlotMathDefinition math = new SlotMathLoader().load(gameId, MATH_VERSION).math();
        RtpSimulationService service = new RtpSimulationService(
                new SlotMathRegistry(Map.of(gameId + "@" + MATH_VERSION, math)),
                new GridGenerationEngine(),
                new ReelEvaluator(List.of(new PaylineWinEvaluator(), new WaysWinEvaluator()),
                        new CascadeEngine(new GridGenerationEngine())),
                new PickCollectEngine(), new RespinEngine(new GridGenerationEngine()), new WildFeatureEngine());

        RtpReport report = service.run(RtpSimulationRequest.builder()
                .gameId(gameId)
                .mathVersion(MATH_VERSION)
                .bet(BET)
                .spinsBaseGame(SPINS)
                .spinsBonusBuyFreeSpins(0)
                .pickStrategy(RtpSimulationRequest.PickStrategy.RANDOM_UNOPENED)
                .build(), "calibrate-" + gameId);

        RtpReport.Channel base = report.channels().get("BASE_GAME");
        double measured = base.rtpPercent().doubleValue();
        double target = math.targetRtp().doubleValue();

        // Only the pay-table-driven part of RTP scales. Pick & Collect pays in credits and Hold & Spin
        // pays in coin values and jackpot multipliers, so both are flat under a pay-table rescale -
        // solving `s = target / measured` would badly overshoot on a game with a fat feature.
        // Measuring the line+free-spins share separately makes the scale exact in one pass.
        double lineFs = measureLineAndFreeSpinsOnly(gameId, math);
        double flatFeatures = measured - lineFs;
        double exactScale = (target - flatFeatures) / lineFs;

        System.out.printf("CALIBRATE [%s]: measured=%.4f%% (lineFs=%.4f%% features=%.4f%%) target=%.4f%%"
                        + " -> payTable scale s=%.6f (naive %.6f)%n",
                gameId, measured, lineFs, flatFeatures, target, exactScale, target / measured);
        System.out.printf("   featureEntries: pick=%,d respin=%,d freeSpins=%,d%n",
                report.pickEntries(), report.respinEntries(), report.freeSpinTriggers());
        System.out.printf("   hitFrequency=%s%% maxWin=%sx (cap %dx) over %,d spins%n",
                base.hitFrequencyPercent(), base.maxWinMultiplier(),
                math.limits().maxWinPerRoundMultiplier(), SPINS);
        base.winDistribution().forEach(b ->
                System.out.printf("   %-11s %,12d  %s%%%n", b.label(), b.count(), b.sharePercent()));
    }

    /**
     * The share of RTP that <em>does</em> move with the pay table: line wins plus the free spins they
     * fund. Measured by re-running the same simulator against a variant of the game with its two
     * credit-denominated features switched off - Pick &amp; Collect via {@code triggerOneInN = 0} and
     * Hold &amp; Spin via a disabled respin block. Cascades stay on, because a tumble pays line wins and
     * therefore scales with the rest of them.
     */
    private double measureLineAndFreeSpinsOnly(String gameId, SlotMathDefinition math) {
        PickCollectConfig pick = math.pickCollect();
        SlotMathDefinition featureless = new SlotMathDefinition(
                math.gameId(), math.mathVersion(), math.targetRtp(), math.grid(), math.winModel(),
                math.waysDirection(), math.wildFeatures(),
                math.symbols(), math.paylines(), math.payTable(), math.reelStrips(),
                math.scatterTriggers(), math.freeSpins(), math.powerBet(), math.bonusBuyOptions(),
                new PickCollectConfig(pick.boardSize(), pick.completion(), pick.tileDistribution(),
                        pick.maxFeatureWinMultiplier(), 0),
                math.cascades(), RespinConfig.disabled(), math.limits(), math.betConfig());

        RtpSimulationService service = new RtpSimulationService(
                new SlotMathRegistry(Map.of(gameId + "@" + MATH_VERSION, featureless)),
                new GridGenerationEngine(),
                new ReelEvaluator(List.of(new PaylineWinEvaluator(), new WaysWinEvaluator()),
                        new CascadeEngine(new GridGenerationEngine())),
                new PickCollectEngine(), new RespinEngine(new GridGenerationEngine()), new WildFeatureEngine());

        return service.run(RtpSimulationRequest.builder()
                .gameId(gameId)
                .mathVersion(MATH_VERSION)
                .bet(BET)
                .spinsBaseGame(SPINS)
                .spinsBonusBuyFreeSpins(0)
                .pickStrategy(RtpSimulationRequest.PickStrategy.RANDOM_UNOPENED)
                .build(), "calibrate-linefs-" + gameId)
                .channels().get("BASE_GAME").rtpPercent().doubleValue();
    }
}
