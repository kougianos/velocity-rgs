package com.velocity.rgs.blackjack.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

/**
 * Loads a {@link BlackjackGameDefinition} (presentation + math) from
 * {@code src/main/resources/games/<gameId>/<mathVersion>.json}. Uses a dedicated strict {@link ObjectMapper}
 * (fail on unknown fields, big-decimal for numerics) so any malformed blackjack JSON fails fast at startup.
 * Mirrors the slot {@code SlotMathLoader} / {@code RouletteGameLoader}.
 */
@Slf4j
@Component
public class BlackjackGameLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true)
            .registerModule(new ParameterNamesModule());

    public BlackjackGameDefinition load(String gameId, String mathVersion) {
        String path = "games/" + gameId + "/" + mathVersion + ".json";
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            throw new IllegalStateException("Blackjack game JSON not found on classpath: " + path);
        }
        log.info("Loading blackjack game {} from {}", gameId + "@" + mathVersion, path);
        try (InputStream in = resource.getInputStream()) {
            BlackjackGameDefinition def = MAPPER.readValue(in, BlackjackGameDefinition.class);
            if (!gameId.equals(def.gameId()) || !mathVersion.equals(def.mathVersion())) {
                throw new IllegalStateException(String.format(
                        "Blackjack game JSON header mismatch for %s/%s: file declares %s/%s",
                        gameId, mathVersion, def.gameId(), def.mathVersion()));
            }
            return def;
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to parse blackjack game JSON " + path + ": " + e.getMessage(), e);
        }
    }
}
