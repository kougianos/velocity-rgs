package com.velocity.rgs.game.feature.bonusbuy;

import com.velocity.rgs.common.error.ErrorCode;
import com.velocity.rgs.common.error.RgsException;
import com.velocity.rgs.math.config.BonusBuyOption;
import com.velocity.rgs.math.config.SlotMathDefinition;
import com.velocity.rgs.math.config.SlotMathLoader;
import com.velocity.rgs.math.domain.BonusBuyType;
import com.velocity.rgs.session.domain.GameSession;
import com.velocity.rgs.session.domain.GameState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BonusBuyPolicyServiceTest {

    private BonusBuyPolicyService service;
    private BonusBuyPolicyProperties props;
    private SlotMathDefinition math;
    private GameSession session;

    @BeforeEach
    void setUp() {
        props = new BonusBuyPolicyProperties();
        service = new BonusBuyPolicyService(props);
        math = new SlotMathLoader().load("aztec-fire", "v1").math();
        session = GameSession.builder()
                .sessionId("ses-1")
                .playerId("p-1")
                .gameId("aztec-fire")
                .mathVersion("v1")
                .currency("EUR")
                .currentState(GameState.BASE_GAME)
                .currentBet(BigDecimal.ONE)
                .remainingFreeSpins(0)
                .accumulatedFreeSpinsWin(BigDecimal.ZERO)
                .build();
    }

    @Test
    void rejectsWhenFeatureGloballyDisabled() {
        props.setEnabled(false);
        assertThatThrownBy(() -> service.requireOption(math, BonusBuyType.FREE_SPINS_BUY,
                session, new BigDecimal("100"), BigDecimal.ONE, null))
                .isInstanceOf(RgsException.class)
                .extracting(e -> ((RgsException) e).getErrorCode())
                .isEqualTo(ErrorCode.BONUS_BUY_DISABLED);
    }

    @Test
    void rejectsJurisdictionNotInAllowlist() {
        props.setAllowedJurisdictions(List.of("US"));
        assertThatThrownBy(() -> service.requireOption(math, BonusBuyType.FREE_SPINS_BUY,
                session, new BigDecimal("100"), BigDecimal.ONE, "DE"))
                .isInstanceOf(RgsException.class)
                .extracting(e -> ((RgsException) e).getErrorCode())
                .isEqualTo(ErrorCode.BONUS_BUY_DISABLED);
    }

    @Test
    void rejectsWhenSessionNotInBaseGame() {
        session.setCurrentState(GameState.FREE_SPINS_LOOP);
        assertThatThrownBy(() -> service.requireOption(math, BonusBuyType.FREE_SPINS_BUY,
                session, new BigDecimal("100"), BigDecimal.ONE, null))
                .isInstanceOf(RgsException.class)
                .extracting(e -> ((RgsException) e).getErrorCode())
                .isEqualTo(ErrorCode.ILLEGAL_STATE_TRANSITION);
    }

    @Test
    void rejectsWhenBalanceBelowCost() {
        assertThatThrownBy(() -> service.requireOption(math, BonusBuyType.FREE_SPINS_BUY,
                session, new BigDecimal("5.00"), BigDecimal.ONE, null))
                .isInstanceOf(RgsException.class)
                .extracting(e -> ((RgsException) e).getErrorCode())
                .isEqualTo(ErrorCode.INSUFFICIENT_FUNDS);
    }

    @Test
    void returnsMatchingOptionWhenAllChecksPass() {
        BonusBuyOption option = service.requireOption(math, BonusBuyType.FREE_SPINS_BUY,
                session, new BigDecimal("10000.00"), BigDecimal.ONE, null);

        assertThat(option.buyType()).isEqualTo(BonusBuyType.FREE_SPINS_BUY);
        assertThat(option.targetState()).isEqualTo(GameState.FREE_SPINS_AWAITING);
        assertThat(option.costMultiplier()).isEqualByComparingTo("100");
    }
}
