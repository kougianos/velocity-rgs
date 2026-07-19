package com.velocity.rgs.slot.math.config;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One rung of the coin-value ladder a landing Hold &amp; Spin coin draws from. {@code value} is in bet
 * multiples, so a coin is worth the same fraction of the stake whatever the player wagers.
 */
public record CoinValueWeight(int weight, BigDecimal value) {

    public CoinValueWeight {
        Objects.requireNonNull(value, "respins.coinValues.value");
        if (weight <= 0) {
            throw new IllegalArgumentException("respins.coinValues.weight must be > 0, found " + weight);
        }
        if (value.signum() <= 0) {
            throw new IllegalArgumentException("respins.coinValues.value must be > 0, found " + value);
        }
    }
}
