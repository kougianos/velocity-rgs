package com.velocity.rgs.slot.fsm;

import com.velocity.rgs.session.domain.GameState;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

/**
 * Sealed projection of the persistent {@link GameState} enum into a typed FSM input. Each variant carries
 * only the data the {@link SessionStateMachine} needs to validate a command and emit the next state — the
 * persistent row in {@code game_session} remains the source of truth for the full snapshot.
 */
public sealed interface SessionState
        permits SessionState.BaseGame,
                SessionState.FreeSpinsAwaiting,
                SessionState.FreeSpinsLoop,
                SessionState.PickCollectAwaiting,
                SessionState.PickCollectLoop {

    GameState gameState();

    record BaseGame() implements SessionState {
        @Override public GameState gameState() { return GameState.BASE_GAME; }
    }

    record FreeSpinsAwaiting(int remainingFreeSpins, BigDecimal triggerBet) implements SessionState {
        public FreeSpinsAwaiting {
            if (remainingFreeSpins <= 0) {
                throw new IllegalArgumentException("remainingFreeSpins must be > 0");
            }
            Objects.requireNonNull(triggerBet, "triggerBet");
        }
        @Override public GameState gameState() { return GameState.FREE_SPINS_AWAITING; }
    }

    record FreeSpinsLoop(int remainingFreeSpins, BigDecimal accumulatedWin, BigDecimal triggerBet)
            implements SessionState {
        public FreeSpinsLoop {
            if (remainingFreeSpins < 0) {
                throw new IllegalArgumentException("remainingFreeSpins must be >= 0");
            }
            Objects.requireNonNull(accumulatedWin, "accumulatedWin");
            Objects.requireNonNull(triggerBet, "triggerBet");
        }
        @Override public GameState gameState() { return GameState.FREE_SPINS_LOOP; }
    }

    record PickCollectAwaiting(Map<String, Object> initialPayload) implements SessionState {
        public PickCollectAwaiting {
            Objects.requireNonNull(initialPayload, "initialPayload");
            initialPayload = Map.copyOf(initialPayload);
        }
        @Override public GameState gameState() { return GameState.PICK_COLLECT_AWAITING; }
    }

    record PickCollectLoop(Map<String, Object> featurePayload) implements SessionState {
        public PickCollectLoop {
            Objects.requireNonNull(featurePayload, "featurePayload");
            featurePayload = Map.copyOf(featurePayload);
        }
        @Override public GameState gameState() { return GameState.PICK_COLLECT_LOOP; }
    }
}
