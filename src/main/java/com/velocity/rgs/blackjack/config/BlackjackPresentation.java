package com.velocity.rgs.blackjack.config;

import com.velocity.rgs.catalog.GameInfo;

import java.util.Objects;

/**
 * Player-facing presentation metadata for a blackjack game — title, marketing copy, theme, and the shared
 * {@link GameInfo} block (rules / payouts / stats). Loaded from the {@code presentation} block of
 * {@code games/<gameId>/<mathVersion>.json}. The table layout is drawn entirely from the math model, so there
 * is no wheel/reel/symbol detail here. Surfaced to the browser client through the public game catalog.
 */
public record BlackjackPresentation(
        String title,
        String tagline,
        String description,
        String logo,
        String theme,
        String volatility,
        GameInfo info
) {

    public BlackjackPresentation {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(tagline, "tagline");
        Objects.requireNonNull(logo, "logo");
        Objects.requireNonNull(theme, "theme");
        Objects.requireNonNull(volatility, "volatility");
        if (title.isBlank()) {
            throw new IllegalArgumentException("presentation.title must not be blank");
        }
    }
}
