package com.velocity.rgs.session.service;

import com.velocity.rgs.common.error.ErrorCode;
import com.velocity.rgs.common.error.RgsException;
import com.velocity.rgs.testsupport.RgsIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@RgsIntegrationTest
class PlayerActionLockIntegrationTest {

    @Autowired private PlayerActionLock lock;
    @Autowired private StringRedisTemplate redisTemplate;

    private String playerId;

    @BeforeEach
    void clean() {
        playerId = "p-" + UUID.randomUUID();
        redisTemplate.delete(PlayerActionLock.key(playerId));
    }

    @Test
    void acquireAndReleaseClearsKey() {
        PlayerActionLock.LockHandle handle = lock.acquire(playerId);

        assertThat(handle.playerId()).isEqualTo(playerId);
        assertThat(redisTemplate.opsForValue().get(PlayerActionLock.key(playerId)))
                .isEqualTo(handle.token());

        assertThat(lock.release(handle)).isTrue();
        assertThat(redisTemplate.hasKey(PlayerActionLock.key(playerId))).isFalse();
    }

    @Test
    void secondAcquireBlockedWhileFirstHolds() {
        PlayerActionLock.LockHandle first = lock.acquire(playerId);

        Optional<PlayerActionLock.LockHandle> second = lock.tryAcquire(playerId);
        assertThat(second).isEmpty();

        assertThatThrownBy(() -> lock.acquire(playerId))
                .isInstanceOf(RgsException.class)
                .extracting(e -> ((RgsException) e).getErrorCode())
                .isEqualTo(ErrorCode.SESSION_VERSION_CONFLICT);

        lock.release(first);
    }

    @Test
    void releaseWithForeignTokenDoesNotDeleteKey() {
        lock.acquire(playerId);

        boolean released = lock.release(new PlayerActionLock.LockHandle(playerId, "not-the-token"));

        assertThat(released).isFalse();
        assertThat(redisTemplate.hasKey(PlayerActionLock.key(playerId))).isTrue();
    }

    @Test
    void lockExpiresAfterTtlAndCanBeReacquired() throws InterruptedException {
        lock.tryAcquire(playerId, Duration.ofMillis(200)).orElseThrow();
        Thread.sleep(400);

        Optional<PlayerActionLock.LockHandle> next = lock.tryAcquire(playerId);

        assertThat(next).isPresent();
        lock.release(next.get());
    }
}
