package com.velocity.rgs.roulette.config;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Table limits for a roulette game, from {@code math.limits}: the most that may be wagered on a single spot
 * ({@link #maxBetPerSpot()}) and across the whole layout in one spin ({@link #maxTotalBet()}). The server
 * enforces both on every spin so a tampered client cannot exceed the table's exposure.
 */
public record RouletteLimits(BigDecimal maxBetPerSpot, BigDecimal maxTotalBet) {

    public RouletteLimits {
        Objects.requireNonNull(maxBetPerSpot, "limits.maxBetPerSpot");
        Objects.requireNonNull(maxTotalBet, "limits.maxTotalBet");
        if (maxBetPerSpot.signum() <= 0) {
            throw new IllegalArgumentException("limits.maxBetPerSpot must be positive");
        }
        if (maxTotalBet.compareTo(maxBetPerSpot) < 0) {
            throw new IllegalArgumentException("limits.maxTotalBet must be >= limits.maxBetPerSpot");
        }
    }
}
