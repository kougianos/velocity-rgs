package com.velocity.rgs.slot.service;

import com.velocity.rgs.common.error.ErrorCode;
import com.velocity.rgs.common.error.RgsException;
import com.velocity.rgs.slot.feature.pickcollect.PickCollectEngine;
import com.velocity.rgs.slot.math.config.SlotMathDefinition;
import com.velocity.rgs.slot.math.config.SlotMathLoader;
import com.velocity.rgs.slot.math.config.SlotMathRegistry;
import com.velocity.rgs.slot.math.engine.GridGenerationEngine;
import com.velocity.rgs.slot.math.engine.ReelEvaluator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fast (non-{@code slow}) behavioural cover for {@link RtpSimulationService}'s channel selection.
 *
 * <p>The convergence guards live in {@link RtpSimulationVerificationTest} and
 * {@link BonusBuyRtpVerificationTest}; this class only asserts that asking for a channel a game does
 * not offer fails as a client error rather than a 500, and that every shipped slot can actually run
 * the bonus-buy channel.
 */
class RtpSimulationServiceTest {

    private static final String MATH_VERSION = "v1";

    private RtpSimulationService newService(String gameId, SlotMathDefinition math) {
        SlotMathRegistry registry = new SlotMathRegistry(Map.of(gameId + "@" + MATH_VERSION, math));
        return new RtpSimulationService(registry, new GridGenerationEngine(),
                new ReelEvaluator(), new PickCollectEngine());
    }

    private SlotMathDefinition load(String gameId) {
        return new SlotMathLoader().load(gameId, MATH_VERSION).math();
    }

    /** Same math, bonus-buy options stripped - stands in for a slot authored without a buy. */
    private SlotMathDefinition withoutBonusBuy(SlotMathDefinition m) {
        return new SlotMathDefinition(
                m.gameId(), m.mathVersion(), m.targetRtp(), m.grid(), m.winModel(), m.symbols(),
                m.paylines(), m.payTable(), m.reelStrips(), m.scatterTriggers(), m.freeSpins(),
                m.powerBet(), List.of(), m.pickCollect(), m.limits(), m.betConfig());
    }

    private RtpSimulationRequest request(String gameId, long buys) {
        return RtpSimulationRequest.builder()
                .gameId(gameId)
                .mathVersion(MATH_VERSION)
                .bet(new BigDecimal("1.00"))
                .spinsBaseGame(0)
                .spinsPowerBet(0)
                .spinsBonusBuyFreeSpins(buys)
                .build();
    }

    /**
     * Asking for bonus-buy spins on a game that offers no buy is a bad request, not a server fault.
     * It previously escaped as a bare {@link IllegalStateException}, which the global handler maps to
     * {@code INTERNAL_ERROR} - a 500 for what is squarely a caller error. The live spin path already
     * raises {@code BONUS_BUY_DISABLED} here ({@code SessionStateMachine.findBuyOption}); the
     * simulator now agrees with it.
     */
    @Test
    void bonusBuyChannelOnGameWithoutABuyIsAClientError() {
        SlotMathDefinition math = withoutBonusBuy(load("aztec-fire"));
        RtpSimulationService service = newService("aztec-fire", math);

        assertThatThrownBy(() -> service.run(request("aztec-fire", 10), "no-buy"))
                .isInstanceOf(RgsException.class)
                .hasMessageContaining("aztec-fire")
                .extracting(ex -> ((RgsException) ex).getErrorCode())
                .isEqualTo(ErrorCode.BONUS_BUY_DISABLED);
    }

    /** Requesting zero bonus-buy spins must stay legal even when the game offers no buy. */
    @Test
    void zeroBonusBuySpinsNeverTouchesTheBuyOption() {
        SlotMathDefinition math = withoutBonusBuy(load("aztec-fire"));
        RtpSimulationService service = newService("aztec-fire", math);

        assertThatCode(() -> service.run(request("aztec-fire", 0), "zero-buys"))
                .doesNotThrowAnyException();
    }

    /** Every shipped slot offers a purchasable free-spins feature the simulator can price. */
    @ParameterizedTest(name = "{0} can run the bonus-buy channel")
    @ValueSource(strings = {"aztec-fire", "frost-crown", "inferno-riches", "jade-tiger"})
    void everyShippedSlotSupportsTheBonusBuyChannel(String gameId) {
        SlotMathDefinition math = load(gameId);
        RtpSimulationService service = newService(gameId, math);

        RtpReport report = service.run(request(gameId, 500), "buy-smoke-" + gameId);

        RtpReport.Channel buy = report.channels().get("BONUS_BUY_FREE_SPINS");
        assertThat(buy.spins()).isEqualTo(500);
        assertThat(buy.totalBet()).isGreaterThan(BigDecimal.ZERO);
    }
}
