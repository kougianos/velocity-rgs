package com.velocity.rgs.blackjack.config;

/** One entry in the {@code rgs.blackjack.catalog} list: which game JSON to load at startup. */
public record BlackjackCatalogEntry(String gameId, String mathVersion) {
}
