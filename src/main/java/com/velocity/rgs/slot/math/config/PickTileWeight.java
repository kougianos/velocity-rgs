package com.velocity.rgs.slot.math.config;

import com.velocity.rgs.slot.math.domain.PickTileType;

import java.util.List;
import java.util.Objects;

/**
 * Tile distribution entry per A.4. {@code valueRange} is required for {@code CREDITS} and {@code MULTIPLIER}
 * tile types and forbidden for others.
 */
public record PickTileWeight(PickTileType type, int weight, int[] valueRange) {

    public PickTileWeight {
        Objects.requireNonNull(type, "tileDistribution.type");
        if (weight <= 0) {
            throw new IllegalArgumentException("tileDistribution.weight must be > 0");
        }
        boolean rangeExpected = type == PickTileType.CREDITS || type == PickTileType.MULTIPLIER;
        if (rangeExpected) {
            if (valueRange == null || valueRange.length != 2 || valueRange[0] > valueRange[1]) {
                throw new IllegalArgumentException(
                        "tileDistribution.valueRange required and must be [min,max] for " + type);
            }
        } else if (valueRange != null) {
            throw new IllegalArgumentException("tileDistribution.valueRange must be absent for " + type);
        }
    }

    public List<Integer> rangeAsList() {
        return valueRange == null ? List.of() : List.of(valueRange[0], valueRange[1]);
    }
}
