package com.velocity.rgs.slot.math.domain;

import java.util.Objects;

/**
 * Single payline definition. {@code coords} is an ordered list of {@code [row, col]} pairs (zero-indexed,
 * row 0 = top) walked left to right.
 */
public record Payline(int id, int[][] coords) {

    public Payline {
        Objects.requireNonNull(coords, "coords");
        if (coords.length == 0) {
            throw new IllegalArgumentException("payline coords cannot be empty");
        }
        for (int[] c : coords) {
            if (c == null || c.length != 2) {
                throw new IllegalArgumentException("each coord must be [row, col]");
            }
            if (c[0] < 0 || c[1] < 0) {
                throw new IllegalArgumentException("coord values must be non-negative");
            }
        }
    }
}
