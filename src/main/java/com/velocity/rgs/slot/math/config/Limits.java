package com.velocity.rgs.slot.math.config;

public record Limits(int maxWinPerRoundMultiplier) {

    public Limits {
        if (maxWinPerRoundMultiplier <= 0) {
            throw new IllegalArgumentException("limits.maxWinPerRoundMultiplier must be > 0");
        }
    }
}
