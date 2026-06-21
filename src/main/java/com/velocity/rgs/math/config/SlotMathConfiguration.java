package com.velocity.rgs.math.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wires the math subsystem. At startup, iterates the configured game catalog (A.5) and asks
 * {@link SlotMathLoader} to materialize each {@link SlotMathDefinition}; failures abort context startup
 * per the strict fail-fast contract of A.4.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(MathCatalogProperties.class)
public class SlotMathConfiguration {

    @Bean
    public SlotMathRegistry slotMathRegistry(SlotMathLoader loader, MathCatalogProperties properties) {
        Map<String, SlotMathDefinition> registry = new LinkedHashMap<>();
        for (GameCatalogEntry entry : properties.catalog()) {
            SlotMathDefinition def = loader.load(entry.gameId(), entry.mathVersion());
            registry.put(SlotMathRegistry.key(entry.gameId(), entry.mathVersion()), def);
            log.info("Registered math {}@{}: {} symbols, {} paylines",
                    def.gameId(), def.mathVersion(), def.symbols().size(), def.paylines().size());
        }
        return new SlotMathRegistry(registry);
    }
}
