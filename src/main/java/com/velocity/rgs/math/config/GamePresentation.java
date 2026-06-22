package com.velocity.rgs.math.config;

import java.util.Map;
import java.util.Objects;

/**
 * Player-facing presentation metadata for a game — title, marketing copy, theme and the per-symbol display
 * (glyph + friendly name) — loaded from the {@code presentation} block of
 * {@code games/<gameId>/<mathVersion>.json}. Deliberately kept separate from {@link SlotMathDefinition} so
 * math and look-and-feel stay decoupled. Surfaced to the browser client through the public game catalog
 * (A.5) so nothing about how a game looks is hardcoded on the client.
 */
public record GamePresentation(
        String title,
        String tagline,
        String description,
        String logo,
        String theme,
        String volatility,
        Integer spinDurationMillis,
        Map<Integer, SymbolDisplay> symbols
) {

    /** How long the reels visibly spin before they begin to settle, when a game JSON omits the value. */
    public static final int DEFAULT_SPIN_DURATION_MILLIS = 600;

    public GamePresentation {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(tagline, "tagline");
        Objects.requireNonNull(logo, "logo");
        Objects.requireNonNull(theme, "theme");
        Objects.requireNonNull(volatility, "volatility");
        Objects.requireNonNull(symbols, "symbols");
        if (spinDurationMillis == null) {
            spinDurationMillis = DEFAULT_SPIN_DURATION_MILLIS;
        } else if (spinDurationMillis <= 0) {
            throw new IllegalArgumentException("presentation.spinDurationMillis must be positive");
        }
        if (title.isBlank()) {
            throw new IllegalArgumentException("presentation.title must not be blank");
        }
        if (symbols.isEmpty()) {
            throw new IllegalArgumentException("presentation.symbols must not be empty");
        }
        symbols = Map.copyOf(symbols);
    }

    /** How a single reel symbol (keyed by its math symbol id) is rendered on the client. */
    public record SymbolDisplay(String glyph, String name) {

        public SymbolDisplay {
            Objects.requireNonNull(glyph, "glyph");
            Objects.requireNonNull(name, "name");
        }
    }
}
