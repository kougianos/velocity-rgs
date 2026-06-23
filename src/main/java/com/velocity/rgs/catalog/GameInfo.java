package com.velocity.rgs.catalog;

import java.util.List;
import java.util.Objects;

/**
 * The rich, "show game info" content a player reads before playing: free-form marketing paragraphs, the
 * headline stat cards (theme / volatility / RTP), and the spec sheet (game type, lines/bets, bet values, max
 * exposure …). Entirely display-ready strings authored in the game JSON so the client renders the panel
 * verbatim and nothing about it is hardcoded. Shared by every game type (slots and roulette) — both
 * presentations embed one. Optional — a game JSON may omit the whole {@code info} block.
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
