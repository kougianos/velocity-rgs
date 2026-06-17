package com.velocity.rgs.math.config;

import java.util.Objects;

public record GameCatalogEntry(String gameId, String mathVersion) {

    public GameCatalogEntry {
        Objects.requireNonNull(gameId, "gameId");
        Objects.requireNonNull(mathVersion, "mathVersion");
        if (gameId.isBlank() || mathVersion.isBlank()) {
            throw new IllegalArgumentException("gameId/mathVersion must not be blank");
        }
    }
}
