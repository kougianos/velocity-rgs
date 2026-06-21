package com.velocity.rgs.math.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Game catalog (A.5) declared in {@code application.yml} under {@code rgs.math.catalog}. Every entry is
 * loaded into the {@link SlotMathRegistry} at startup.
 */
@ConfigurationProperties(prefix = "rgs.math")
public record MathCatalogProperties(List<GameCatalogEntry> catalog) {

    public MathCatalogProperties {
        catalog = catalog == null ? List.of() : List.copyOf(catalog);
        if (catalog.isEmpty()) {
            throw new IllegalArgumentException("rgs.math.catalog must define at least one game");
        }
    }
}
