package com.velocity.rgs.blackjack.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Blackjack game catalog declared in {@code application.yml} under {@code rgs.blackjack.catalog}. Additive -
 * an empty list is allowed (the app boots with slots/roulette only); every entry present is loaded into the
 * {@link BlackjackCatalogRegistry} at startup.
 */
@ConfigurationProperties(prefix = "rgs.blackjack")
public record BlackjackCatalogProperties(List<BlackjackCatalogEntry> catalog) {

    public BlackjackCatalogProperties {
        catalog = catalog == null ? List.of() : List.copyOf(catalog);
    }
}
