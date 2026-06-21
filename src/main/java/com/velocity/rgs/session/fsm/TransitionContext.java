package com.velocity.rgs.session.fsm;

import com.velocity.rgs.math.config.SlotMathDefinition;

import java.util.Objects;

/**
 * Read-only inputs threaded into a {@link SessionStateMachine} transition. Carries the math definition
 * (so the FSM can look up {@code bonusBuyOptions}, {@code freeSpins.betLockedToTriggerBet}, etc.) plus
 * the currency the resulting {@link MonetaryEffect}s must be denominated in.
 */
public record TransitionContext(SlotMathDefinition math, String currency) {

    public TransitionContext {
        Objects.requireNonNull(math, "math");
        Objects.requireNonNull(currency, "currency");
    }
}
