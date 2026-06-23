package com.velocity.rgs.slot.math.config;

import com.velocity.rgs.common.error.ErrorCode;
import com.velocity.rgs.common.error.RgsException;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;

/**
 * Read-only registry of all loaded slot math definitions, keyed by {@code gameId} + {@code mathVersion}.
 */
public final class SlotMathRegistry {

    private final Map<String, SlotMathDefinition> byKey;

    public SlotMathRegistry(Map<String, SlotMathDefinition> byKey) {
        Objects.requireNonNull(byKey, "byKey");
        if (byKey.isEmpty()) {
            throw new IllegalArgumentException("registry must contain at least one math definition");
        }
        this.byKey = Map.copyOf(byKey);
    }

    public SlotMathDefinition require(String gameId, String mathVersion) {
        SlotMathDefinition def = byKey.get(key(gameId, mathVersion));
        if (def == null) {
            throw new RgsException(ErrorCode.VALIDATION_ERROR,
                    "Unknown game/mathVersion: " + gameId + "/" + mathVersion);
        }
        return def;
    }

    public Collection<SlotMathDefinition> all() {
        return byKey.values();
    }

    static String key(String gameId, String mathVersion) {
        return gameId + "@" + mathVersion;
    }
}
