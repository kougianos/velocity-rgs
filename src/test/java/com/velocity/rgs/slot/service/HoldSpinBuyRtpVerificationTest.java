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
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the price of Dragon Hoard's purchasable Hold &amp; Spin.
 *
 * <p>A bonus buy is the one place where a mispriced feature is directly exploitable: the player picks
 * when to pay, so a buy whose RTP sits above 100% is a standing invitation to buy it repeatedly and
 * nothing else. This asserts the purchased feature returns the same 96% the rest of the game does.
 *
 * <p>The buy is the widest channel in the catalog - every purchase resolves a feature whose payout
 * spans a few times the stake to the 2,000x GRAND - so the tolerance is sized to it the same way
 * {@link RtpSimulationVerificationTest}'s per-game tolerance is, by measurement rather than by
 * widening until green.
 */
@Tag("slow")
class HoldSpinBuyRtpVerificationTest {

    private static final String GAME_ID = "dragon-hoard";
    private static final String MATH_VERSION = "v1";
    private static final long BUYS = Long.getLong("rtp.holdSpinBuys", 400_000L);
    private static final BigDecimal TOLERANCE =
            new BigDecimal(System.getProperty("rtp.holdSpinTolerance", "1.5"));

    @Test
    void purchasedHoldSpinReturnsTheGamesDeclaredRtp() {
        SlotMathDefinition math = new SlotMathLoader().load(GAME_ID, MATH_VERSION).math();
        RtpSimulationService service = new RtpSimulationService(
                new SlotMathRegistry(Map.of(GAME_ID + "@" + MATH_VERSION, math)),
                new GridGenerationEngine(), new ReelEvaluator(), new PickCollectEngine(),
                new RespinEngine(new GridGenerationEngine()), new WildFeatureEngine());

        RtpReport report = service.run(RtpSimulationRequest.builder()
                .gameId(GAME_ID)
                .mathVersion(MATH_VERSION)
                .bet(new BigDecimal("1.00"))
                .spinsBaseGame(0)
                .spinsBonusBuyFreeSpins(0)
                .spinsBonusBuyHoldSpin(BUYS)
                .build(), "holdspin-buy-verify");

        RtpReport.Channel buy = report.channels().get("BONUS_BUY_HOLD_SPIN");
        BigDecimal target = math.targetRtp();
        BigDecimal deviation = buy.rtpPercent().subtract(target).abs();

        System.out.printf("HOLD&SPIN BUY verification [%s]: target=%s%% simulated=%s%% deviation=%s pp "
                        + "hitFrequency=%s%% maxWin=%sx over %,d buys%n",
                GAME_ID, target, buy.rtpPercent(), deviation, buy.hitFrequencyPercent(),
                buy.maxWinMultiplier(), BUYS);

        assertThat(buy.spins()).isEqualTo(BUYS);
        assertThat(deviation)
                .as("purchased Hold & Spin RTP %s%% must be within %s pp of the declared %s%%",
                        buy.rtpPercent(), TOLERANCE, target)
                .isLessThanOrEqualTo(TOLERANCE);
    }
}
