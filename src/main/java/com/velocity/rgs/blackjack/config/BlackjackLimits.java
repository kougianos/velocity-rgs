package com.velocity.rgs.blackjack.config;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Table limits for a blackjack game - the {@code math.limits} block of the game JSON. Only the maximum base
 * bet is bounded here; doubles/splits/insurance derive their size from the base bet and the player's balance.
 */
public record BlackjackLimits(BigDecimal maxBet) {

    public BlackjackLimits {
        Objects.requireNonNull(maxBet, "limits.maxBet");
        if (maxBet.signum() <= 0) {
            throw new IllegalArgumentException("limits.maxBet must be positive, found " + maxBet);
        }
    }
}
