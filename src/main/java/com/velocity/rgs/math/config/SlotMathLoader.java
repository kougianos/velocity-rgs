package com.velocity.rgs.math.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

/**
 * Loads a {@link GameDefinition} (presentation + math) from
 * {@code src/main/resources/games/<gameId>/<mathVersion>.json} (A.4). Uses a dedicated strict
 * {@link ObjectMapper} (fail on unknown fields, big-decimal for numerics) so any malformed game JSON
 * fails fast at startup.
 */
@Slf4j
@Component
public class SlotMathLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true)
            .registerModule(new ParameterNamesModule());

    public GameDefinition load(String gameId, String mathVersion) {
        String path = "games/" + gameId + "/" + mathVersion + ".json";
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            throw new IllegalStateException("Game JSON not found on classpath: " + path);
        }
        log.info("Loading game {} from {}", gameId + "@" + mathVersion, path);
        try (InputStream in = resource.getInputStream()) {
            GameDefinition def = MAPPER.readValue(in, GameDefinition.class);
            if (!gameId.equals(def.gameId()) || !mathVersion.equals(def.mathVersion())) {
                throw new IllegalStateException(String.format(
                        "Game JSON header mismatch for %s/%s: file declares %s/%s",
                        gameId, mathVersion, def.gameId(), def.mathVersion()));
            }
            return def;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse game JSON " + path + ": " + e.getMessage(), e);
        }
    }
}
