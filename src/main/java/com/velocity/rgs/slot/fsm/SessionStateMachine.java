package com.velocity.rgs.slot.fsm;

import com.velocity.rgs.common.error.ErrorCode;
import com.velocity.rgs.common.error.RgsException;
import com.velocity.rgs.common.money.Money;
import com.velocity.rgs.slot.math.config.BonusBuyOption;
import com.velocity.rgs.slot.math.domain.BonusBuyType;
import com.velocity.rgs.session.domain.GameCommand;
import com.velocity.rgs.session.domain.GameState;
import com.velocity.rgs.wallet.domain.WalletTransactionType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static com.velocity.rgs.slot.fsm.SessionCommand.BuyFeatureCommand;
import static com.velocity.rgs.slot.fsm.SessionCommand.PickCommand;
import static com.velocity.rgs.slot.fsm.SessionCommand.SpinCommand;
import static com.velocity.rgs.slot.fsm.SessionCommand.StartFreeSpinsCommand;
import static com.velocity.rgs.slot.fsm.SessionCommand.StartPickCollectCommand;
import static com.velocity.rgs.slot.fsm.SessionCommand.StartRespinCommand;
import static com.velocity.rgs.slot.fsm.SessionState.BaseGame;
import static com.velocity.rgs.slot.fsm.SessionState.FreeSpinsAwaiting;
import static com.velocity.rgs.slot.fsm.SessionState.FreeSpinsLoop;
import static com.velocity.rgs.slot.fsm.SessionState.PickCollectAwaiting;
import static com.velocity.rgs.slot.fsm.SessionState.PickCollectLoop;
import static com.velocity.rgs.slot.fsm.SessionState.RespinAwaiting;
import static com.velocity.rgs.slot.fsm.SessionState.RespinLoop;

/**
 * Pure FSM evaluator. Given the current {@link SessionState}, the inbound {@link SessionCommand}, and the
 * {@link TransitionContext}, returns the next state + monetary intent + reason codes + available actions.
 *
 * <p>Stateless and idempotent - performs no I/O. {@code SlotEngineService} (M5) is responsible for
 * acquiring the player action lock, executing {@link MonetaryEffect}s against {@code WalletGateway},
 * persisting the resulting session row, and refreshing the Redis cache.</p>
 */
@Component
public class SessionStateMachine {

    public TransitionResult transition(SessionState current, SessionCommand command, TransitionContext context) {
        return switch (current) {
            case BaseGame base -> fromBase(base, command, context);
            case FreeSpinsAwaiting fsa -> fromFreeSpinsAwaiting(fsa, command);
            case FreeSpinsLoop fsl -> fromFreeSpinsLoop(fsl, command, context);
            case PickCollectAwaiting pca -> fromPickCollectAwaiting(pca, command);
            case PickCollectLoop pcl -> fromPickCollectLoop(pcl, command);
            case RespinAwaiting ra -> fromRespinAwaiting(ra, command);
            case RespinLoop rl -> fromRespinLoop(rl, command);
        };
    }

    // ----- BASE_GAME -------------------------------------------------------

    private TransitionResult fromBase(BaseGame current, SessionCommand command, TransitionContext ctx) {
        return switch (command) {
            case SpinCommand spin -> new TransitionResult(
                    current,
                    new MonetaryEffect.Debit(Money.of(spin.betSize(), ctx.currency()), WalletTransactionType.BET),
                    List.of(),
                    baseGameActions(ctx));
            case BuyFeatureCommand buy -> resolveBuy(buy, ctx);
            default -> illegal(current.gameState(), command);
        };
    }

    private TransitionResult resolveBuy(BuyFeatureCommand buy, TransitionContext ctx) {
        BonusBuyOption option = findBuyOption(ctx, buy.buyType());
        // A stake times a multiplier is a money amount, so it has to land on the currency's minor
        // units before it becomes Money - which rejects anything finer. Integer multipliers happen to
        // come out at scale 2 from a scale-2 stake; a calibrated fractional one (x230.76) does not,
        // and used to fail the purchase outright with a scale error.
        BigDecimal cost = buyCost(buy.betSize(), option, ctx.currency());
        MonetaryEffect debit = new MonetaryEffect.Debit(
                Money.of(cost, ctx.currency()), WalletTransactionType.BONUS_BUY);

        return switch (option.targetState()) {
            case FREE_SPINS_AWAITING -> new TransitionResult(
                    new FreeSpinsAwaiting(extractFreeSpinsAwarded(option), buy.betSize()),
                    debit,
                    List.of("ENTERED_VIA_BUY"),
                    List.of(GameCommand.START_FREE_SPINS));
            case PICK_COLLECT_AWAITING -> new TransitionResult(
                    new PickCollectAwaiting(option.initialFeaturePayload()),
                    debit,
                    List.of("ENTERED_VIA_BUY"),
                    List.of(GameCommand.START_PICK_COLLECT));
            // The locked coins are drawn by SlotEngineService (they need an RNG, which the FSM has
            // no business holding); this transition only establishes that the purchase is legal and
            // what it costs. The payload it carries here is replaced with the real one there.
            case RESPIN_AWAITING -> new TransitionResult(
                    new RespinAwaiting(option.initialFeaturePayload(), buy.betSize()),
                    debit,
                    List.of("ENTERED_VIA_BUY"),
                    List.of(GameCommand.START_RESPIN));
            default -> throw new RgsException(ErrorCode.VALIDATION_ERROR,
                    "Bonus buy targetState not supported: " + option.targetState());
        };
    }

    // ----- FREE_SPINS_AWAITING --------------------------------------------

    private TransitionResult fromFreeSpinsAwaiting(FreeSpinsAwaiting current, SessionCommand command) {
        return switch (command) {
            case StartFreeSpinsCommand ignored -> new TransitionResult(
                    new FreeSpinsLoop(current.remainingFreeSpins(), BigDecimal.ZERO.setScale(2),
                            current.triggerBet()),
                    MonetaryEffect.none(),
                    List.of(),
                    List.of(GameCommand.SPIN));
            default -> illegal(current.gameState(), command);
        };
    }

    // ----- FREE_SPINS_LOOP ------------------------------------------------

    private TransitionResult fromFreeSpinsLoop(FreeSpinsLoop current, SessionCommand command, TransitionContext ctx) {
        return switch (command) {
            case SpinCommand spin -> {
                if (ctx.math().freeSpins().betLockedToTriggerBet()
                        && spin.betSize().compareTo(current.triggerBet()) != 0) {
                    throw new RgsException(ErrorCode.VALIDATION_ERROR,
                            "Free spin bet is locked to triggering bet " + current.triggerBet());
                }
                if (spin.powerBetActive() && !ctx.math().freeSpins().powerBetPersists()) {
                    throw new RgsException(ErrorCode.VALIDATION_ERROR,
                            "Power Bet is not permitted during free spins for this game");
                }
                yield new TransitionResult(
                        current,
                        MonetaryEffect.none(),
                        List.of(),
                        List.of(GameCommand.SPIN));
            }
            default -> illegal(current.gameState(), command);
        };
    }

    // ----- PICK_COLLECT_AWAITING ------------------------------------------

    private TransitionResult fromPickCollectAwaiting(PickCollectAwaiting current, SessionCommand command) {
        return switch (command) {
            case StartPickCollectCommand ignored -> new TransitionResult(
                    new PickCollectLoop(current.initialPayload()),
                    MonetaryEffect.none(),
                    List.of(),
                    List.of(GameCommand.PICK));
            default -> illegal(current.gameState(), command);
        };
    }

    // ----- PICK_COLLECT_LOOP ----------------------------------------------

    private TransitionResult fromPickCollectLoop(PickCollectLoop current, SessionCommand command) {
        return switch (command) {
            case PickCommand ignored -> new TransitionResult(
                    current,
                    MonetaryEffect.none(),
                    List.of(),
                    List.of(GameCommand.PICK));
            default -> illegal(current.gameState(), command);
        };
    }

    // ----- RESPIN_AWAITING ------------------------------------------------

    private TransitionResult fromRespinAwaiting(RespinAwaiting current, SessionCommand command) {
        return switch (command) {
            case StartRespinCommand ignored -> new TransitionResult(
                    new RespinLoop(current.featurePayload(), current.triggerBet()),
                    MonetaryEffect.none(),
                    List.of(),
                    List.of(GameCommand.SPIN));
            default -> illegal(current.gameState(), command);
        };
    }

    // ----- RESPIN_LOOP ----------------------------------------------------

    /**
     * A respin costs nothing: the Hold &amp; Spin feature is funded by the round that triggered it, so
     * every iteration is a free re-draw of the unlocked cells. The stake is pinned to the triggering
     * bet for the same reason - a player cannot raise their exposure part-way through a feature they
     * have already paid for, and Power Bet is likewise not re-offered mid-feature.
     */
    private TransitionResult fromRespinLoop(RespinLoop current, SessionCommand command) {
        return switch (command) {
            case SpinCommand spin -> {
                if (spin.betSize().compareTo(current.triggerBet()) != 0) {
                    throw new RgsException(ErrorCode.VALIDATION_ERROR,
                            "Respin bet is locked to the triggering bet " + current.triggerBet());
                }
                if (spin.powerBetActive()) {
                    throw new RgsException(ErrorCode.VALIDATION_ERROR,
                            "Power Bet is not permitted during Hold & Spin respins");
                }
                yield new TransitionResult(
                        current,
                        MonetaryEffect.none(),
                        List.of(),
                        List.of(GameCommand.SPIN));
            }
            default -> illegal(current.gameState(), command);
        };
    }

    // ----- helpers --------------------------------------------------------

    private List<GameCommand> baseGameActions(TransitionContext ctx) {
        return ctx.math().bonusBuyOptions().isEmpty()
                ? List.of(GameCommand.SPIN)
                : List.of(GameCommand.SPIN, GameCommand.BUY_FEATURE);
    }

    /**
     * What a bonus buy costs, rounded to the currency's minor units. Shared with
     * {@code SlotEngineService} so the debit, the persisted purchase event and the response can never
     * disagree by a rounding step.
     */
    public static BigDecimal buyCost(BigDecimal betSize, BonusBuyOption option, String currency) {
        return betSize.multiply(option.costMultiplier())
                .setScale(Money.minorUnitScale(currency), RoundingMode.HALF_UP);
    }

    private BonusBuyOption findBuyOption(TransitionContext ctx, BonusBuyType buyType) {
        return ctx.math().bonusBuyOptions().stream()
                .filter(o -> o.buyType() == buyType)
                .findFirst()
                .orElseThrow(() -> new RgsException(ErrorCode.BONUS_BUY_DISABLED,
                        "Bonus buy type not offered: " + buyType));
    }

    private int extractFreeSpinsAwarded(BonusBuyOption option) {
        Object raw = option.initialFeaturePayload().get("freeSpinsAwarded");
        if (raw instanceof Number n) {
            return n.intValue();
        }
        throw new RgsException(ErrorCode.VALIDATION_ERROR,
                "Bonus buy option missing 'freeSpinsAwarded' for FREE_SPINS_AWAITING target");
    }

    private TransitionResult illegal(GameState state, SessionCommand command) {
        throw new RgsException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                "Command " + command.command() + " is not legal in state " + state);
    }
}
