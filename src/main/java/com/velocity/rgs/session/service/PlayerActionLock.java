package com.velocity.rgs.session.service;

import com.velocity.rgs.common.error.ErrorCode;
import com.velocity.rgs.common.error.RgsException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-player short-lived contention guard (M4 Task 4.7 / A.10). Uses Redis {@code SET NX PX <ttl>} with
 * a caller-owned UUID so release only deletes the key when the value still belongs to the holder
 * (compare-and-delete via Lua). Default TTL is 3s (A.10) — the lock is a guard, not the source of truth.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlayerActionLock {

    public static final Duration DEFAULT_TTL = Duration.ofSeconds(3);
    private static final String KEY_PREFIX = "rgs:lock:player:";

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redisTemplate;

    public Optional<LockHandle> tryAcquire(String playerId) {
        return tryAcquire(playerId, DEFAULT_TTL);
    }

    public Optional<LockHandle> tryAcquire(String playerId, Duration ttl) {
        String token = UUID.randomUUID().toString();
        Boolean ok = redisTemplate.opsForValue().setIfAbsent(key(playerId), token, ttl);
        if (Boolean.TRUE.equals(ok)) {
            return Optional.of(new LockHandle(playerId, token));
        }
        return Optional.empty();
    }

    public LockHandle acquire(String playerId) {
        return tryAcquire(playerId).orElseThrow(() -> new RgsException(
                ErrorCode.SESSION_VERSION_CONFLICT,
                "Another action is already in flight for player: " + playerId));
    }

    public boolean release(LockHandle handle) {
        if (handle == null) {
            return false;
        }
        Long deleted = redisTemplate.execute(RELEASE_SCRIPT,
                List.of(key(handle.playerId())), handle.token());
        return deleted != null && deleted > 0;
    }

    static String key(String playerId) {
        return KEY_PREFIX + playerId;
    }

    /**
     * Opaque lock ticket. Pass it back to {@link #release(LockHandle)} when the protected action completes.
     */
    public record LockHandle(String playerId, String token) {
    }
}
