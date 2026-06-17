package com.velocity.rgs.session.domain;

/**
 * Canonical session-level commands per A.0.1. The wire/API enum; the M4 sealed
 * {@link SessionCommand} hierarchy projects to these values for telemetry/logging.
 */
public enum GameCommand {
    SPIN,
    START_FREE_SPINS,
    BUY_FEATURE,
    START_PICK_COLLECT,
    PICK
}
