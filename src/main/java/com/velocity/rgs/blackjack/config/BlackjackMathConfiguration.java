package com.velocity.rgs.blackjack.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wires the blackjack math subsystem. At startup, iterates the configured blackjack catalog and asks
 * {@link BlackjackGameLoader} to materialize each {@link BlackjackGameDefinition}; failures abort context
 * startup (strict fail-fast). Exposes the {@link BlackjackCatalogRegistry} consumed by both the public
 * catalog endpoint and the blackjack engine. Mirrors {@code RouletteMathConfiguration}.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(BlackjackCatalogProperties.class)
public class BlackjackMathConfiguration {

    @Bean
    public BlackjackCatalogRegistry blackjackCatalogRegistry(BlackjackGameLoader loader,
                                                             BlackjackCatalogProperties properties) {
        Map<String, BlackjackGameDefinition> registry = new LinkedHashMap<>();
        for (BlackjackCatalogEntry entry : properties.catalog()) {
            BlackjackGameDefinition def = loader.load(entry.gameId(), entry.mathVersion());
            registry.put(BlackjackCatalogRegistry.key(entry.gameId(), entry.mathVersion()), def);
            log.info("Registered blackjack game {}@{}: variant={}, {} decks, S17={}, BJ pays {}",
                    def.gameId(), def.mathVersion(), def.math().variant(), def.math().decks(),
                    !def.math().dealerHitsSoft17(), def.math().blackjackPayout());
        }
        return new BlackjackCatalogRegistry(registry);
    }
}
