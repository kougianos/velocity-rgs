package com.velocity.rgs.slot.math.config;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A Hold &amp; Spin jackpot tier: hold {@code minCoins} coins when the feature settles and the round
 * pays {@code multiplier} times the stake on top of the coins themselves.
 *
 * <p>A tier whose {@code minCoins} equals the cell count of the grid is the full-grid jackpot - the
 * headline prize of the mechanic, and the reason a player keeps respinning a nearly-full board.
 */
public record RespinJackpot(String tier, int minCoins, BigDecimal multiplier) {

    public RespinJackpot {
        Objects.requireNonNull(tier, "respins.jackpots.tier");
        Objects.requireNonNull(multiplier, "respins.jackpots.multiplier");
        if (tier.isBlank()) {
            throw new IllegalArgumentException("respins.jackpots.tier must not be blank");
        }
        if (minCoins < 1) {
            throw new IllegalArgumentException(
                    "respins.jackpots.minCoins must be >= 1, found " + minCoins);
        }
        if (multiplier.signum() <= 0) {
            throw new IllegalArgumentException(
                    "respins.jackpots.multiplier must be > 0, found " + multiplier);
        }
    }
}
