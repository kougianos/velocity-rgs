package com.velocity.rgs.roulette.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wires the roulette math subsystem. At startup, iterates the configured roulette catalog and asks
 * {@link RouletteGameLoader} to materialize each {@link RouletteGameDefinition}; failures abort context
 * startup (strict fail-fast). Exposes the {@link RouletteCatalogRegistry} consumed by both the public
 * catalog endpoint and the roulette engine. Mirrors the slot {@code SlotMathConfiguration}.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(RouletteCatalogProperties.class)
public class RouletteMathConfiguration {

    @Bean
    public RouletteCatalogRegistry rouletteCatalogRegistry(RouletteGameLoader loader,
                                                           RouletteCatalogProperties properties) {
        Map<String, RouletteGameDefinition> registry = new LinkedHashMap<>();
        for (RouletteCatalogEntry entry : properties.catalog()) {
            RouletteGameDefinition def = loader.load(entry.gameId(), entry.mathVersion());
            registry.put(RouletteCatalogRegistry.key(entry.gameId(), entry.mathVersion()), def);
            log.info("Registered roulette game {}@{}: variant={}, {} bet types, {} pockets",
                    def.gameId(), def.mathVersion(), def.math().variant(),
                    def.math().betTypes().size(), def.math().pocketCount());
        }
        return new RouletteCatalogRegistry(registry);
    }
}
