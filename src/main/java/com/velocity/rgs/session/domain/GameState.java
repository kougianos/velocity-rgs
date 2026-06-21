package com.velocity.rgs.session.domain;

/**
 * Canonical session FSM state names per A.0.1. The wire/API enum; the M4 sealed
 * {@code SessionState} hierarchy will project to/from these values.
 */
public enum GameState {
    BASE_GAME,
    FREE_SPINS_AWAITING,
    FREE_SPINS_LOOP,
    PICK_COLLECT_AWAITING,
    PICK_COLLECT_LOOP
}
