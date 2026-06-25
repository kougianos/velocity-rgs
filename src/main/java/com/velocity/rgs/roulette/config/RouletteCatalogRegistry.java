package com.velocity.rgs.roulette.config;

import com.velocity.rgs.common.error.ErrorCode;
import com.velocity.rgs.common.error.RgsException;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;

/**
 * Read-only registry of every loaded {@link RouletteGameDefinition} (presentation + math), keyed by
 * {@code gameId} + {@code mathVersion}. Backs both the public game catalog endpoint and the roulette engine
 * (which reads {@code require(...).math()}).
 */
public final class RouletteCatalogRegistry {

    private final Map<String, RouletteGameDefinition> byKey;

    public RouletteCatalogRegistry(Map<String, RouletteGameDefinition> byKey) {
        Objects.requireNonNull(byKey, "byKey");
        this.byKey = Map.copyOf(byKey);
    }

    public static String key(String gameId, String mathVersion) {
        return gameId + "@" + mathVersion;
    }

    public RouletteGameDefinition require(String gameId, String mathVersion) {
        RouletteGameDefinition def = byKey.get(key(gameId, mathVersion));
        if (def == null) {
            throw new RgsException(ErrorCode.VALIDATION_ERROR,
                    "Unknown roulette game/mathVersion: " + gameId + "/" + mathVersion);
        }
        return def;
    }

    /** First registered math version for a game id - the default a fresh session binds to. */
    public RouletteGameDefinition requireByGameId(String gameId) {
        return byKey.values().stream()
                .filter(d -> d.gameId().equals(gameId))
                .findFirst()
                .orElseThrow(() -> new RgsException(ErrorCode.VALIDATION_ERROR,
                        "No roulette definition registered for gameId=" + gameId));
    }

    public boolean contains(String gameId) {
        return byKey.values().stream().anyMatch(d -> d.gameId().equals(gameId));
    }

    public Collection<RouletteGameDefinition> all() {
        return byKey.values();
    }
}
