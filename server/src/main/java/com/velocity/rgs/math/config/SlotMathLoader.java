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
 * Loads {@link SlotMathDefinition} from {@code src/main/resources/math/<gameId>/<mathVersion>.json} (A.4).
 * Uses a dedicated strict {@link ObjectMapper} (fail on unknown fields, big-decimal for numerics) so
 * malformed math JSON fails fast at startup.
 */
@Slf4j
@Component
public class SlotMathLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true)
            .registerModule(new ParameterNamesModule());

    public SlotMathDefinition load(String gameId, String mathVersion) {
        String path = "math/" + gameId + "/" + mathVersion + ".json";
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            throw new IllegalStateException("Math JSON not found on classpath: " + path);
        }
        log.info("Loading slot math {} from {}", gameId + "@" + mathVersion, path);
        try (InputStream in = resource.getInputStream()) {
            SlotMathDefinition def = MAPPER.readValue(in, SlotMathDefinition.class);
            if (!gameId.equals(def.gameId()) || !mathVersion.equals(def.mathVersion())) {
                throw new IllegalStateException(String.format(
                        "Math JSON header mismatch for %s/%s: file declares %s/%s",
                        gameId, mathVersion, def.gameId(), def.mathVersion()));
            }
            return def;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse math JSON " + path + ": " + e.getMessage(), e);
        }
    }
}
