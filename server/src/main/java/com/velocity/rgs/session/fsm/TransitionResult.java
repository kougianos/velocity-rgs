package com.velocity.rgs.session.fsm;

import com.velocity.rgs.session.domain.GameCommand;

import java.util.List;
import java.util.Objects;

/**
 * Pure output of a {@link SessionStateMachine} transition. Holds the next {@link SessionState}, the
 * monetary intent (executed downstream by the caller against {@code WalletGateway}), reason codes for
 * audit/replay, and the list of {@link GameCommand}s legal in the new state (mirrored to the
 * {@code availableActions} field on the API response per A.7).
 */
public record TransitionResult(
        SessionState newState,
        MonetaryEffect monetaryEffect,
        List<String> reasonCodes,
        List<GameCommand> availableActions
) {

    public TransitionResult {
        Objects.requireNonNull(newState, "newState");
        Objects.requireNonNull(monetaryEffect, "monetaryEffect");
        Objects.requireNonNull(reasonCodes, "reasonCodes");
        Objects.requireNonNull(availableActions, "availableActions");
        reasonCodes = List.copyOf(reasonCodes);
        availableActions = List.copyOf(availableActions);
    }
}
