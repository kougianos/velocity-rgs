package com.velocity.rgs.common.idempotency;

import com.velocity.rgs.common.error.ErrorCode;
import com.velocity.rgs.common.error.RgsException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Write-through Postgres-backed idempotency store with optional Redis acceleration cache.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyStore {

    private final IdempotencyRecordRepository repository;
    private final StringRedisTemplate redisTemplate;

    @Value("${rgs.idempotency.redis-cache-enabled:true}")
    private boolean redisCacheEnabled;

    public Optional<IdempotencyResult> lookup(String scope, String key) {
        if (redisCacheEnabled) {
            try {
                String cached = redisTemplate.opsForValue().get(redisKey(scope, key));
                if (cached != null) {
                    String[] parts = cached.split("\\|", 3);
                    if (parts.length == 3) {
                        return Optional.of(new IdempotencyResult(
                                Integer.parseInt(parts[0]), parts[2], parts[1]));
                    }
                }
            } catch (RuntimeException ex) {
                log.debug("Redis idempotency lookup failed, falling back to DB: {}", ex.getMessage());
            }
        }
        return repository.findByScopeAndKey(scope, key)
                .filter(r -> r.getState() == IdempotencyRecord.State.COMPLETED)
                .map(r -> new IdempotencyResult(r.getStatusCode(), r.getResponseBody(), r.getPayloadHash()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void store(String scope, String key, String payloadHash,
                      int statusCode, String responseBody, long ttlHours) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(Duration.ofHours(ttlHours));
        IdempotencyRecord record = IdempotencyRecord.builder()
                .scope(scope)
                .key(key)
                .payloadHash(payloadHash)
                .responseBody(responseBody)
                .statusCode(statusCode)
                .createdAt(now)
                .expiresAt(expiresAt)
                .state(IdempotencyRecord.State.COMPLETED)
                .build();
        repository.save(record);

        if (redisCacheEnabled) {
            try {
                redisTemplate.opsForValue().set(
                        redisKey(scope, key),
                        statusCode + "|" + payloadHash + "|" + (responseBody == null ? "" : responseBody),
                        Duration.ofHours(ttlHours));
            } catch (RuntimeException ex) {
                log.debug("Redis idempotency cache write failed: {}", ex.getMessage());
            }
        }
    }

    public void assertHashMatches(String scope, String key, String incomingHash, String storedHash) {
        if (!storedHash.equals(incomingHash)) {
            throw new RgsException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                    "Idempotency key '" + key + "' on scope '" + scope + "' reused with a different payload");
        }
    }

    private String redisKey(String scope, String key) {
        return "rgs:idem:" + scope + ":" + key;
    }
}
