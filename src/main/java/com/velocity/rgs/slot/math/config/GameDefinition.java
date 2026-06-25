package com.velocity.rgs.slot.math.config;

import java.util.Objects;

/**
 * The complete configuration for a single game as loaded from {@code games/<gameId>/<mathVersion>.json}:
 * its {@link GamePresentation} (player-facing look and copy) plus its {@link SlotMathDefinition} (reel math,
 * pay table, features). One file per game is the single source of truth for everything about that game; the
 * math-only registry used by the engine and the public catalog used by the client are both derived from it.
 */
public record GameDefinition(GamePresentation presentation, SlotMathDefinition math) {

    public GameDefinition {
        Objects.requireNonNull(presentation, "presentation");
        Objects.requireNonNull(math, "math");
    }

    /** Convenience delegate - the gameId lives on the math model. */
    public String gameId() {
        return math.gameId();
    }

    /** Convenience delegate - the mathVersion lives on the math model. */
    public String mathVersion() {
        return math.mathVersion();
    }
}
