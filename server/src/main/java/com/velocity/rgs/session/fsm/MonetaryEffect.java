package com.velocity.rgs.session.fsm;

import com.velocity.rgs.common.money.Money;
import com.velocity.rgs.wallet.domain.WalletTransactionType;

import java.util.Objects;

/**
 * Sealed instruction emitted by {@link SessionStateMachine}. The FSM never touches the wallet directly;
 * it returns the intent and lets {@code SlotEngineService} (M5) execute it via {@code WalletGateway}.
 */
public sealed interface MonetaryEffect
        permits MonetaryEffect.Debit, MonetaryEffect.Credit, MonetaryEffect.None {

    record Debit(Money amount, WalletTransactionType type) implements MonetaryEffect {
        public Debit {
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(type, "type");
        }
    }

    record Credit(Money amount, WalletTransactionType type) implements MonetaryEffect {
        public Credit {
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(type, "type");
        }
    }

    record None() implements MonetaryEffect {
        public static final None INSTANCE = new None();
    }

    static MonetaryEffect none() {
        return None.INSTANCE;
    }
}
