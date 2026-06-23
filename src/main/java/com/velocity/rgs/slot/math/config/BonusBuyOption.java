package com.velocity.rgs.slot.math.config;

import com.velocity.rgs.slot.math.domain.BonusBuyType;
import com.velocity.rgs.session.domain.GameState;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

/**
 * Bonus Buy option per A.4. {@code costMultiplier} is multiplied by the active base bet to produce the
 * debit amount. {@code initialFeaturePayload} is opaque math-level bootstrap data (e.g. {@code freeSpinsAwarded},
 * {@code boardSize}, {@code maxPicks}) consumed by the feature initializer in later milestones.
 *
 * <p>{@code freeSpinsWinMultiplier} boosts every win during a <em>bought</em> free-spins round (the
 * "you start with higher multipliers" mechanic of industry bonus buys). It lets the buy stay at an
 * industry-standard spin count (~10–15) and an industry-standard cost (~80–150× bet) while still
 * returning the game's target RTP — the feature is made richer per spin rather than longer. It applies
 * <em>only</em> to the purchased feature; organically triggered free spins are unaffected. Defaults to
 * {@code 1} (no boost) when absent.
 */
public record BonusBuyOption(
        BonusBuyType buyType,
        BigDecimal costMultiplier,
        GameState targetState,
        Map<String, Object> initialFeaturePayload,
        BigDecimal freeSpinsWinMultiplier
) {

    public BonusBuyOption {
        Objects.requireNonNull(buyType, "bonusBuyOption.buyType");
        Objects.requireNonNull(costMultiplier, "bonusBuyOption.costMultiplier");
        Objects.requireNonNull(targetState, "bonusBuyOption.targetState");
        Objects.requireNonNull(initialFeaturePayload, "bonusBuyOption.initialFeaturePayload");
        if (costMultiplier.signum() <= 0) {
            throw new IllegalArgumentException("bonusBuyOption.costMultiplier must be > 0");
        }
        if (freeSpinsWinMultiplier == null) {
            freeSpinsWinMultiplier = BigDecimal.ONE;
        }
        if (freeSpinsWinMultiplier.signum() <= 0) {
            throw new IllegalArgumentException("bonusBuyOption.freeSpinsWinMultiplier must be > 0");
        }
        initialFeaturePayload = Map.copyOf(initialFeaturePayload);
    }
}
