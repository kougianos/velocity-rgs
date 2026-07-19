package com.velocity.rgs.slot.fsm;

import com.velocity.rgs.slot.math.domain.BonusBuyType;
import com.velocity.rgs.session.domain.GameCommand;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Sealed command hierarchy fed into {@link SessionStateMachine}. The same {@code SpinCommand} is reused
 * across {@code BASE_GAME} (debits the wallet) and {@code FREE_SPINS_LOOP} (free iteration); only the
 * current {@link SessionState} differentiates the financial semantics.
 */
public sealed interface SessionCommand
        permits SessionCommand.SpinCommand,
                SessionCommand.StartFreeSpinsCommand,
                SessionCommand.BuyFeatureCommand,
                SessionCommand.StartPickCollectCommand,
                SessionCommand.StartRespinCommand,
                SessionCommand.PickCommand {

    GameCommand command();

    record SpinCommand(BigDecimal betSize, boolean powerBetActive) implements SessionCommand {
        public SpinCommand {
            Objects.requireNonNull(betSize, "betSize");
            if (betSize.signum() <= 0) {
                throw new IllegalArgumentException("betSize must be > 0");
            }
        }
        @Override public GameCommand command() { return GameCommand.SPIN; }
    }

    record StartFreeSpinsCommand() implements SessionCommand {
        @Override public GameCommand command() { return GameCommand.START_FREE_SPINS; }
    }

    record BuyFeatureCommand(BonusBuyType buyType, BigDecimal betSize) implements SessionCommand {
        public BuyFeatureCommand {
            Objects.requireNonNull(buyType, "buyType");
            Objects.requireNonNull(betSize, "betSize");
            if (betSize.signum() <= 0) {
                throw new IllegalArgumentException("betSize must be > 0");
            }
        }
        @Override public GameCommand command() { return GameCommand.BUY_FEATURE; }
    }

    record StartPickCollectCommand() implements SessionCommand {
        @Override public GameCommand command() { return GameCommand.START_PICK_COLLECT; }
    }

    record StartRespinCommand() implements SessionCommand {
        @Override public GameCommand command() { return GameCommand.START_RESPIN; }
    }

    record PickCommand(int position) implements SessionCommand {
        public PickCommand {
            if (position < 0) {
                throw new IllegalArgumentException("position must be >= 0");
            }
        }
        @Override public GameCommand command() { return GameCommand.PICK; }
    }
}
