package com.velocity.rgs.blackjack.config;

import com.velocity.rgs.common.error.ErrorCode;
import com.velocity.rgs.common.error.RgsException;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;

/**
 * Read-only registry of every loaded {@link BlackjackGameDefinition} (presentation + math), keyed by
 * {@code gameId} + {@code mathVersion}. Backs both the public game catalog endpoint and the blackjack engine
 * (which reads {@code require(...).math()}). Mirrors {@code RouletteCatalogRegistry}.
 */
public final class BlackjackCatalogRegistry {

    private final Map<String, BlackjackGameDefinition> byKey;

    public BlackjackCatalogRegistry(Map<String, BlackjackGameDefinition> byKey) {
        Objects.requireNonNull(byKey, "byKey");
        this.byKey = Map.copyOf(byKey);
    }

    public static String key(String gameId, String mathVersion) {
        return gameId + "@" + mathVersion;
    }

    public BlackjackGameDefinition require(String gameId, String mathVersion) {
        BlackjackGameDefinition def = byKey.get(key(gameId, mathVersion));
        if (def == null) {
            throw new RgsException(ErrorCode.VALIDATION_ERROR,
                    "Unknown blackjack game/mathVersion: " + gameId + "/" + mathVersion);
        }
        return def;
    }

    /** First registered math version for a game id - the default a fresh session binds to. */
    public BlackjackGameDefinition requireByGameId(String gameId) {
        return byKey.values().stream()
                .filter(d -> d.gameId().equals(gameId))
                .findFirst()
                .orElseThrow(() -> new RgsException(ErrorCode.VALIDATION_ERROR,
                        "No blackjack definition registered for gameId=" + gameId));
    }

    public boolean contains(String gameId) {
        return byKey.values().stream().anyMatch(d -> d.gameId().equals(gameId));
    }

    public Collection<BlackjackGameDefinition> all() {
        return byKey.values();
    }
}
