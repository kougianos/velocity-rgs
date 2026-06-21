package com.velocity.rgs.math.domain;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Objects;

/**
 * Single reel strip: ordered sequence of symbol ids. The grid generator selects a stop index and reads
 * downward with wrapping (per A.4 / Milestone 2 generator semantics).
 */
public record ReelStrip(int[] symbols) {

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public ReelStrip {
        Objects.requireNonNull(symbols, "symbols");
        if (symbols.length == 0) {
            throw new IllegalArgumentException("reel strip cannot be empty");
        }
    }

    public int length() {
        return symbols.length;
    }

    public int symbolAt(int index) {
        int n = symbols.length;
        int normalized = ((index % n) + n) % n;
        return symbols[normalized];
    }
}
