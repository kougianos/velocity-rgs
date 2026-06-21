package com.velocity.rgs.math.config;

import com.velocity.rgs.math.domain.BonusBuyType;
import com.velocity.rgs.session.domain.GameState;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

/**
 * Bonus Buy option per A.4. {@code costMultiplier} is multiplied by the active base bet to produce the
 * debit amount. {@code initialFeaturePayload} is opaque math-level bootstrap data (e.g. {@code freeSpinsAwarded},
 * {@code boardSize}, {@code maxPicks}) consumed by the feature initializer in later milestones.
 */
public record BonusBuyOption(
        BonusBuyType buyType,
        BigDecimal costMultiplier,
        GameState targetState,
        Map<String, Object> initialFeaturePayload
) {

    public BonusBuyOption {
        Objects.requireNonNull(buyType, "bonusBuyOption.buyType");
        Objects.requireNonNull(costMultiplier, "bonusBuyOption.costMultiplier");
        Objects.requireNonNull(targetState, "bonusBuyOption.targetState");
        Objects.requireNonNull(initialFeaturePayload, "bonusBuyOption.initialFeaturePayload");
        if (costMultiplier.signum() <= 0) {
            throw new IllegalArgumentException("bonusBuyOption.costMultiplier must be > 0");
        }
        initialFeaturePayload = Map.copyOf(initialFeaturePayload);
    }
}
