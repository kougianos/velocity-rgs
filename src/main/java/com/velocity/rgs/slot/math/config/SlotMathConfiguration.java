package com.velocity.rgs.slot.math.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wires the math subsystem. At startup, iterates the configured game catalog (A.5) and asks
 * {@link SlotMathLoader} to materialize each {@link GameDefinition}; failures abort context startup per the
 * strict fail-fast contract of A.4. Exposes two derived views of the same loaded definitions: the full
 * {@link GameCatalogRegistry} (presentation + math, for the public catalog) and the math-only
 * {@link SlotMathRegistry} consumed by the game engine.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(MathCatalogProperties.class)
public class SlotMathConfiguration {

    @Bean
    public GameCatalogRegistry gameCatalogRegistry(SlotMathLoader loader, MathCatalogProperties properties) {
        Map<String, GameDefinition> registry = new LinkedHashMap<>();
        for (GameCatalogEntry entry : properties.catalog()) {
            GameDefinition def = loader.load(entry.gameId(), entry.mathVersion());
            registry.put(SlotMathRegistry.key(entry.gameId(), entry.mathVersion()), def);
            log.info("Registered game {}@{}: {} symbols, {} paylines",
                    def.gameId(), def.mathVersion(), def.math().symbols().size(), def.math().paylines().size());
        }
        return new GameCatalogRegistry(registry);
    }

    @Bean
    public SlotMathRegistry slotMathRegistry(GameCatalogRegistry catalog) {
        Map<String, SlotMathDefinition> registry = new LinkedHashMap<>();
        for (GameDefinition def : catalog.all()) {
            registry.put(SlotMathRegistry.key(def.gameId(), def.mathVersion()), def.math());
        }
        return new SlotMathRegistry(registry);
    }
}
