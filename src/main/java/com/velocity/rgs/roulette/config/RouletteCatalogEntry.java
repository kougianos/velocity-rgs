package com.velocity.rgs.roulette.config;

import java.util.Objects;

/** One roulette game declared in {@code application.yml} under {@code rgs.roulette.catalog}. */
public record RouletteCatalogEntry(String gameId, String mathVersion) {

    public RouletteCatalogEntry {
        Objects.requireNonNull(gameId, "gameId");
        Objects.requireNonNull(mathVersion, "mathVersion");
        if (gameId.isBlank() || mathVersion.isBlank()) {
            throw new IllegalArgumentException("gameId/mathVersion must not be blank");
        }
    }
}
