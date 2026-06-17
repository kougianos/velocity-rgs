package com.velocity.rgs.math.domain;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Pay table indexed by {@code symbolId} then {@code matchCount} (e.g. 3, 4, 5) yielding a coefficient that
 * is multiplied by the round bet to produce the line payout.
 */
public record PayTable(Map<Integer, Map<Integer, BigDecimal>> coefficients) {

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public PayTable {
        Objects.requireNonNull(coefficients, "coefficients");
        coefficients = Map.copyOf(coefficients);
    }

    public Optional<BigDecimal> lookup(int symbolId, int matchCount) {
        Map<Integer, BigDecimal> row = coefficients.get(symbolId);
        if (row == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(row.get(matchCount));
    }
}
