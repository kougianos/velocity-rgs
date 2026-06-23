package com.velocity.rgs.roulette.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Roulette game catalog declared in {@code application.yml} under {@code rgs.roulette.catalog}. Additive —
 * an empty list is allowed (the app boots with slots only); every entry present is loaded into the
 * {@link RouletteCatalogRegistry} at startup.
 */
@ConfigurationProperties(prefix = "rgs.roulette")
public record RouletteCatalogProperties(List<RouletteCatalogEntry> catalog) {

    public RouletteCatalogProperties {
        catalog = catalog == null ? List.of() : List.copyOf(catalog);
    }
}
