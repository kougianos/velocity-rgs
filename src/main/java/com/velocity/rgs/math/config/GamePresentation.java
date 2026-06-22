package com.velocity.rgs.math.config;

import java.util.List;
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
        Map<Integer, SymbolDisplay> symbols,
        GameInfo info
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

    /**
     * The rich, "show game info" content a player reads before playing: free-form marketing paragraphs, the
     * headline stat cards (theme / volatility / RTP), and the spec sheet (game type, lines, bet values, max
     * exposure …). Entirely display-ready strings authored in the game JSON so the client renders the panel
     * verbatim and nothing about it is hardcoded. Optional — a game JSON may omit the whole {@code info} block.
     */
    public record GameInfo(
            List<String> paragraphs,
            List<InfoStat> stats,
            List<InfoSpec> specs
    ) {

        public GameInfo {
            paragraphs = paragraphs == null ? List.of() : List.copyOf(paragraphs);
            stats = stats == null ? List.of() : List.copyOf(stats);
            specs = specs == null ? List.of() : List.copyOf(specs);
        }

        /** A headline stat card, e.g. {@code label="RTP"}, {@code value="96.00%"}. */
        public record InfoStat(String label, String value) {

            public InfoStat {
                Objects.requireNonNull(label, "info.stat.label");
                Objects.requireNonNull(value, "info.stat.value");
            }
        }

        /**
         * One row of the spec sheet — a label and one or more value lines (e.g. {@code label="Special
         * Features"} with {@code values=["Coin Collect", "Stepper", "Hold N Spin"]}).
         */
        public record InfoSpec(String label, List<String> values) {

            public InfoSpec {
                Objects.requireNonNull(label, "info.spec.label");
                values = values == null ? List.of() : List.copyOf(values);
            }
        }
    }
}
