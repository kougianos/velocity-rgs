package com.velocity.rgs.session.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocity.rgs.session.domain.GameSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis-backed session JSON cache. Key pattern {@code rgs:session:{playerId}} with 30 min idle TTL,
 * per A.10. Cache is best-effort acceleration only: any Redis failure falls back transparently to the
 * Postgres source of truth (per Persistence Rules).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionCache {

    public static final Duration TTL = Duration.ofMinutes(30);
    private static final String KEY_PREFIX = "rgs:session:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public Optional<GameSession> get(String playerId) {
        try {
            String raw = redisTemplate.opsForValue().get(key(playerId));
            if (raw == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(raw, GameSession.class));
        } catch (RuntimeException | JsonProcessingException ex) {
            log.debug("SessionCache read miss/fail for {}: {}", playerId, ex.getMessage());
            return Optional.empty();
        }
    }

    public void put(GameSession session) {
        try {
            String json = objectMapper.writeValueAsString(session);
            redisTemplate.opsForValue().set(key(session.getPlayerId()), json, TTL);
        } catch (RuntimeException | JsonProcessingException ex) {
            log.debug("SessionCache write fail for {}: {}", session.getPlayerId(), ex.getMessage());
        }
    }

    public void evict(String playerId) {
        try {
            redisTemplate.delete(key(playerId));
        } catch (RuntimeException ex) {
            log.debug("SessionCache evict fail for {}: {}", playerId, ex.getMessage());
        }
    }

    public boolean isCached(String playerId) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key(playerId)));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    static String key(String playerId) {
        return KEY_PREFIX + playerId;
    }
}
