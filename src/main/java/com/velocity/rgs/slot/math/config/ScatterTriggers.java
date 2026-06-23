package com.velocity.rgs.slot.math.config;

public record ScatterTriggers(int minCount, int freeSpinsAwarded, int retriggerAwards) {

    public ScatterTriggers {
        if (minCount < 2) {
            throw new IllegalArgumentException("scatterTriggers.minCount must be >= 2");
        }
        if (freeSpinsAwarded <= 0) {
            throw new IllegalArgumentException("scatterTriggers.freeSpinsAwarded must be > 0");
        }
        if (retriggerAwards < 0) {
            throw new IllegalArgumentException("scatterTriggers.retriggerAwards must be >= 0");
        }
    }
}
