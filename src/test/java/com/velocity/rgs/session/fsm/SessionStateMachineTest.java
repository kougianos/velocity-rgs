package com.velocity.rgs.session.fsm;

import com.velocity.rgs.common.error.RgsException;
import com.velocity.rgs.common.money.Money;
import com.velocity.rgs.math.config.SlotMathDefinition;
import com.velocity.rgs.math.config.SlotMathLoader;
import com.velocity.rgs.math.domain.BonusBuyType;
import com.velocity.rgs.session.domain.GameCommand;
import com.velocity.rgs.session.domain.GameState;
import com.velocity.rgs.wallet.domain.WalletTransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.velocity.rgs.common.error.ErrorCode.BONUS_BUY_DISABLED;
import static com.velocity.rgs.common.error.ErrorCode.ILLEGAL_STATE_TRANSITION;
import static com.velocity.rgs.common.error.ErrorCode.VALIDATION_ERROR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionStateMachineTest {

    private final SessionStateMachine machine = new SessionStateMachine();
    private SlotMathDefinition math;
    private TransitionContext ctx;

    @BeforeEach
    void loadMath() {
        math = new SlotMathLoader().load("aztec-fire", "v1").math();
        ctx = new TransitionContext(math, "EUR");
    }

    // ----- legal transitions ----------------------------------------------

    @Test
    void baseGameSpinDebitsBetAndStaysInBaseGame() {
        TransitionResult res = machine.transition(
                new SessionState.BaseGame(),
                new SessionCommand.SpinCommand(new BigDecimal("1.00"), false),
                ctx);

        assertThat(res.newState()).isInstanceOf(SessionState.BaseGame.class);
        assertThat(res.monetaryEffect()).isInstanceOf(MonetaryEffect.Debit.class);
        MonetaryEffect.Debit debit = (MonetaryEffect.Debit) res.monetaryEffect();
        assertThat(debit.type()).isEqualTo(WalletTransactionType.BET);
        assertThat(debit.amount()).isEqualTo(Money.of(new BigDecimal("1.00"), "EUR"));
        assertThat(res.availableActions()).containsExactlyInAnyOrder(GameCommand.SPIN, GameCommand.BUY_FEATURE);
    }

    @Test
    void baseGameBuyFreeSpinsDebitsCostAndEntersAwaiting() {
        TransitionResult res = machine.transition(
                new SessionState.BaseGame(),
                new SessionCommand.BuyFeatureCommand(BonusBuyType.FREE_SPINS_BUY, new BigDecimal("1.00")),
                ctx);

        assertThat(res.newState()).isInstanceOf(SessionState.FreeSpinsAwaiting.class);
        SessionState.FreeSpinsAwaiting awaiting = (SessionState.FreeSpinsAwaiting) res.newState();
        assertThat(awaiting.remainingFreeSpins()).isEqualTo(12);
        assertThat(awaiting.triggerBet()).isEqualByComparingTo(new BigDecimal("1.00"));
        assertThat(res.monetaryEffect()).isInstanceOf(MonetaryEffect.Debit.class);
        MonetaryEffect.Debit debit = (MonetaryEffect.Debit) res.monetaryEffect();
        assertThat(debit.type()).isEqualTo(WalletTransactionType.BONUS_BUY);
        assertThat(debit.amount()).isEqualTo(Money.of(new BigDecimal("100.00"), "EUR"));
        assertThat(res.reasonCodes()).containsExactly("ENTERED_VIA_BUY");
        assertThat(res.availableActions()).containsExactly(GameCommand.START_FREE_SPINS);
    }

    @Test
    void startFreeSpinsMovesAwaitingToLoop() {
        TransitionResult res = machine.transition(
                new SessionState.FreeSpinsAwaiting(10, new BigDecimal("1.00")),
                new SessionCommand.StartFreeSpinsCommand(),
                ctx);

        assertThat(res.newState()).isInstanceOf(SessionState.FreeSpinsLoop.class);
        SessionState.FreeSpinsLoop loop = (SessionState.FreeSpinsLoop) res.newState();
        assertThat(loop.remainingFreeSpins()).isEqualTo(10);
        assertThat(loop.accumulatedWin()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(res.monetaryEffect()).isInstanceOf(MonetaryEffect.None.class);
        assertThat(res.availableActions()).containsExactly(GameCommand.SPIN);
    }

    @Test
    void freeSpinLoopSpinHasNoMonetaryEffect() {
        TransitionResult res = machine.transition(
                new SessionState.FreeSpinsLoop(5, new BigDecimal("0.00"), new BigDecimal("1.00")),
                new SessionCommand.SpinCommand(new BigDecimal("1.00"), false),
                ctx);

        assertThat(res.newState()).isInstanceOf(SessionState.FreeSpinsLoop.class);
        assertThat(res.monetaryEffect()).isInstanceOf(MonetaryEffect.None.class);
        assertThat(res.availableActions()).containsExactly(GameCommand.SPIN);
    }

    @Test
    void startPickCollectMovesAwaitingToLoop() {
        TransitionResult res = machine.transition(
                new SessionState.PickCollectAwaiting(Map.of("boardSize", 12, "maxPicks", 5)),
                new SessionCommand.StartPickCollectCommand(),
                ctx);

        assertThat(res.newState()).isInstanceOf(SessionState.PickCollectLoop.class);
        assertThat(res.availableActions()).containsExactly(GameCommand.PICK);
    }

    @Test
    void pickCollectLoopPickHasNoMonetaryEffect() {
        TransitionResult res = machine.transition(
                new SessionState.PickCollectLoop(Map.of("boardSize", 12)),
                new SessionCommand.PickCommand(3),
                ctx);

        assertThat(res.newState()).isInstanceOf(SessionState.PickCollectLoop.class);
        assertThat(res.monetaryEffect()).isInstanceOf(MonetaryEffect.None.class);
        assertThat(res.availableActions()).containsExactly(GameCommand.PICK);
    }

    // ----- guard rules ----------------------------------------------------

    @Test
    void freeSpinLoopBetMismatchIsValidationError() {
        assertThatThrownBy(() -> machine.transition(
                new SessionState.FreeSpinsLoop(5, BigDecimal.ZERO, new BigDecimal("1.00")),
                new SessionCommand.SpinCommand(new BigDecimal("2.00"), false),
                ctx))
                .isInstanceOf(RgsException.class)
                .extracting(e -> ((RgsException) e).getErrorCode())
                .isEqualTo(VALIDATION_ERROR);
    }

    @Test
    void powerBetDuringFreeSpinsIsRejectedWhenNotPersisted() {
        assertThatThrownBy(() -> machine.transition(
                new SessionState.FreeSpinsLoop(5, BigDecimal.ZERO, new BigDecimal("1.00")),
                new SessionCommand.SpinCommand(new BigDecimal("1.00"), true),
                ctx))
                .isInstanceOf(RgsException.class)
                .extracting(e -> ((RgsException) e).getErrorCode())
                .isEqualTo(VALIDATION_ERROR);
    }

    // ----- full illegal-transition matrix ---------------------------------

    private static Stream<org.junit.jupiter.params.provider.Arguments> illegalCombos() {
        SessionState base = new SessionState.BaseGame();
        SessionState fsa = new SessionState.FreeSpinsAwaiting(10, new BigDecimal("1.00"));
        SessionState fsl = new SessionState.FreeSpinsLoop(5, BigDecimal.ZERO, new BigDecimal("1.00"));
        SessionState pca = new SessionState.PickCollectAwaiting(Map.of("boardSize", 12));
        SessionState pcl = new SessionState.PickCollectLoop(Map.of("boardSize", 12));

        SessionCommand spin = new SessionCommand.SpinCommand(new BigDecimal("1.00"), false);
        SessionCommand startFs = new SessionCommand.StartFreeSpinsCommand();
        SessionCommand startPc = new SessionCommand.StartPickCollectCommand();
        SessionCommand buy = new SessionCommand.BuyFeatureCommand(BonusBuyType.FREE_SPINS_BUY, new BigDecimal("1.00"));
        SessionCommand pick = new SessionCommand.PickCommand(0);

        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(base, startFs),
                org.junit.jupiter.params.provider.Arguments.of(base, startPc),
                org.junit.jupiter.params.provider.Arguments.of(base, pick),
                org.junit.jupiter.params.provider.Arguments.of(fsa, spin),
                org.junit.jupiter.params.provider.Arguments.of(fsa, startPc),
                org.junit.jupiter.params.provider.Arguments.of(fsa, buy),
                org.junit.jupiter.params.provider.Arguments.of(fsa, pick),
                org.junit.jupiter.params.provider.Arguments.of(fsl, startFs),
                org.junit.jupiter.params.provider.Arguments.of(fsl, startPc),
                org.junit.jupiter.params.provider.Arguments.of(fsl, buy),
                org.junit.jupiter.params.provider.Arguments.of(fsl, pick),
                org.junit.jupiter.params.provider.Arguments.of(pca, spin),
                org.junit.jupiter.params.provider.Arguments.of(pca, startFs),
                org.junit.jupiter.params.provider.Arguments.of(pca, buy),
                org.junit.jupiter.params.provider.Arguments.of(pca, pick),
                org.junit.jupiter.params.provider.Arguments.of(pcl, spin),
                org.junit.jupiter.params.provider.Arguments.of(pcl, startFs),
                org.junit.jupiter.params.provider.Arguments.of(pcl, startPc),
                org.junit.jupiter.params.provider.Arguments.of(pcl, buy));
    }

    @ParameterizedTest
    @MethodSource("illegalCombos")
    void illegalCommandsAreRejected(SessionState state, SessionCommand command) {
        assertThatThrownBy(() -> machine.transition(state, command, ctx))
                .isInstanceOf(RgsException.class)
                .extracting(e -> ((RgsException) e).getErrorCode())
                .isEqualTo(ILLEGAL_STATE_TRANSITION);
    }

    @Test
    void availableActionsForBaseGameOmitBuyWhenCatalogHasNone() {
        SlotMathDefinition stripped = new SlotMathDefinition(
                math.gameId(),
                math.mathVersion(),
                math.targetRtp(),
                math.grid(),
                math.symbols(),
                math.paylines(),
                math.payTable(),
                math.reelStrips(),
                math.scatterTriggers(),
                math.freeSpins(),
                math.powerBet(),
                List.of(),
                math.pickCollect(),
                math.limits(),
                math.betConfig());
        TransitionContext localCtx = new TransitionContext(stripped, "EUR");

        TransitionResult res = machine.transition(
                new SessionState.BaseGame(),
                new SessionCommand.SpinCommand(new BigDecimal("1.00"), false),
                localCtx);

        assertThat(res.availableActions()).containsExactly(GameCommand.SPIN);
    }

    @Test
    void unknownBonusBuyTypeIsBonusBuyDisabled() {
        SlotMathDefinition stripped = new SlotMathDefinition(
                math.gameId(),
                math.mathVersion(),
                math.targetRtp(),
                math.grid(),
                math.symbols(),
                math.paylines(),
                math.payTable(),
                math.reelStrips(),
                math.scatterTriggers(),
                math.freeSpins(),
                math.powerBet(),
                List.of(),
                math.pickCollect(),
                math.limits(),
                math.betConfig());
        TransitionContext localCtx = new TransitionContext(stripped, "EUR");

        assertThatThrownBy(() -> machine.transition(
                new SessionState.BaseGame(),
                new SessionCommand.BuyFeatureCommand(BonusBuyType.FREE_SPINS_BUY, new BigDecimal("1.00")),
                localCtx))
                .isInstanceOf(RgsException.class)
                .extracting(e -> ((RgsException) e).getErrorCode())
                .isEqualTo(BONUS_BUY_DISABLED);
    }

    @Test
    void gameStateAccessorsAreConsistent() {
        assertThat(new SessionState.BaseGame().gameState()).isEqualTo(GameState.BASE_GAME);
        assertThat(new SessionState.FreeSpinsAwaiting(1, BigDecimal.ONE).gameState())
                .isEqualTo(GameState.FREE_SPINS_AWAITING);
        assertThat(new SessionState.FreeSpinsLoop(1, BigDecimal.ZERO, BigDecimal.ONE).gameState())
                .isEqualTo(GameState.FREE_SPINS_LOOP);
        assertThat(new SessionState.PickCollectAwaiting(Map.of()).gameState())
                .isEqualTo(GameState.PICK_COLLECT_AWAITING);
        assertThat(new SessionState.PickCollectLoop(Map.of()).gameState())
                .isEqualTo(GameState.PICK_COLLECT_LOOP);
    }
}
