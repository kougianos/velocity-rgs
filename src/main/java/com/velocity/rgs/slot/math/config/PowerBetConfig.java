package com.velocity.rgs.slot.math.config;

import java.math.BigDecimal;
import java.util.Objects;

public record PowerBetConfig(BigDecimal betMultiplier) {

    public PowerBetConfig {
        Objects.requireNonNull(betMultiplier, "powerBet.betMultiplier");
        if (betMultiplier.signum() <= 0) {
            throw new IllegalArgumentException("powerBet.betMultiplier must be > 0");
        }
    }
}
