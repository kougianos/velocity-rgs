package com.velocity.rgs.roulette.config;

import com.velocity.rgs.catalog.GameInfo;

import java.util.Objects;

/**
 * Player-facing presentation metadata for a roulette game — title, marketing copy, theme, and the shared
 * {@link GameInfo} block (rules / payouts / stats). Loaded from the {@code presentation} block of
 * {@code games/<gameId>/<mathVersion>.json}. Unlike a slot's {@code GamePresentation} there is no per-symbol
 * glyph map — the table layout is drawn from the math model (pockets + bet types). Surfaced to the browser
 * client through the public game catalog so nothing about how the game looks is hardcoded on the client.
 */
public record RoulettePresentation(
        String title,
        String tagline,
        String description,
        String logo,
        String theme,
        String volatility,
        Integer spinDurationMillis,
        GameInfo info
) {

    /** How long the wheel visibly spins before it settles on the result, when a game JSON omits the value. */
    public static final int DEFAULT_SPIN_DURATION_MILLIS = 3200;

    public RoulettePresentation {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(tagline, "tagline");
        Objects.requireNonNull(logo, "logo");
        Objects.requireNonNull(theme, "theme");
        Objects.requireNonNull(volatility, "volatility");
        if (spinDurationMillis == null) {
            spinDurationMillis = DEFAULT_SPIN_DURATION_MILLIS;
        } else if (spinDurationMillis <= 0) {
            throw new IllegalArgumentException("presentation.spinDurationMillis must be positive");
        }
        if (title.isBlank()) {
            throw new IllegalArgumentException("presentation.title must not be blank");
        }
    }
}
