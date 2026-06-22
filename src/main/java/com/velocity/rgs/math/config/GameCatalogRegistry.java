package com.velocity.rgs.math.config;

import com.velocity.rgs.common.error.ErrorCode;
import com.velocity.rgs.common.error.RgsException;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;

/**
 * Read-only registry of every loaded {@link GameDefinition} (presentation + math), keyed by
 * {@code gameId} + {@code mathVersion}. Backs the public game catalog endpoint (A.5). The math-only
 * {@link SlotMathRegistry} consumed by the game engine is derived from this registry at startup.
 */
public final class GameCatalogRegistry {

    private final Map<String, GameDefinition> byKey;

    public GameCatalogRegistry(Map<String, GameDefinition> byKey) {
        Objects.requireNonNull(byKey, "byKey");
        if (byKey.isEmpty()) {
            throw new IllegalArgumentException("registry must contain at least one game definition");
        }
        this.byKey = Map.copyOf(byKey);
    }

    public GameDefinition require(String gameId, String mathVersion) {
        GameDefinition def = byKey.get(SlotMathRegistry.key(gameId, mathVersion));
        if (def == null) {
            throw new RgsException(ErrorCode.VALIDATION_ERROR,
                    "Unknown game/mathVersion: " + gameId + "/" + mathVersion);
        }
        return def;
    }

    public Collection<GameDefinition> all() {
        return byKey.values();
    }
}
