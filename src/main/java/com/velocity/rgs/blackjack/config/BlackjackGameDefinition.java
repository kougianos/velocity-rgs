package com.velocity.rgs.blackjack.config;

import java.util.Objects;

/**
 * The complete configuration for a single blackjack game as loaded from
 * {@code games/<gameId>/<mathVersion>.json}: its {@link BlackjackPresentation} (look and copy) plus its
 * {@link BlackjackMathDefinition} (decks, dealer rule, payouts, limits). One file per game is the single
 * source of truth; the engine consumes the math model and the public catalog is derived from both halves.
 */
public record BlackjackGameDefinition(BlackjackPresentation presentation, BlackjackMathDefinition math) {

    public BlackjackGameDefinition {
        Objects.requireNonNull(presentation, "presentation");
        Objects.requireNonNull(math, "math");
    }

    /** Convenience delegate — the gameId lives on the math model. */
    public String gameId() {
        return math.gameId();
    }

    /** Convenience delegate — the mathVersion lives on the math model. */
    public String mathVersion() {
        return math.mathVersion();
    }
}
