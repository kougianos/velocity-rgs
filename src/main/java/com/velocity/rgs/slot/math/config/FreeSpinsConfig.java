package com.velocity.rgs.slot.math.config;

public record FreeSpinsConfig(boolean betLockedToTriggerBet, boolean powerBetPersists, int maxRetriggerStack) {

    public FreeSpinsConfig {
        if (maxRetriggerStack < 0) {
            throw new IllegalArgumentException("freeSpins.maxRetriggerStack must be >= 0");
        }
    }
}
