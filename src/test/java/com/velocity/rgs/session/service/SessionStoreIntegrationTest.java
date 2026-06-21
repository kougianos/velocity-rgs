package com.velocity.rgs.session.service;

import com.velocity.rgs.session.domain.GameSession;
import com.velocity.rgs.session.domain.GameState;
import com.velocity.rgs.session.persistence.GameSessionRepository;
import com.velocity.rgs.session.persistence.SessionCache;
import com.velocity.rgs.testsupport.RgsIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@RgsIntegrationTest
class SessionStoreIntegrationTest {

    @Autowired private SessionStore store;
    @Autowired private SessionCache cache;
    @Autowired private GameSessionRepository repository;

    private String playerId;
    private String sessionId;

    @BeforeEach
    void clean() {
        repository.deleteAll();
        playerId = "p-" + UUID.randomUUID();
        sessionId = "s-" + UUID.randomUUID();
        cache.evict(playerId);
    }

    @Test
    void saveWritesPostgresAndPopulatesCache() {
        GameSession saved = store.save(newSession());

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getSessionVersion()).isZero();
        assertThat(cache.isCached(playerId)).isTrue();

        Optional<GameSession> cached = cache.get(playerId);
        assertThat(cached).isPresent();
        assertThat(cached.get().getSessionId()).isEqualTo(sessionId);
    }

    @Test
    void findByPlayerIdHitsCacheBeforePostgres() {
        store.save(newSession());

        Optional<GameSession> first = store.findByPlayerId(playerId);
        assertThat(first).isPresent();

        repository.deleteAll();
        Optional<GameSession> second = store.findByPlayerId(playerId);
        assertThat(second).isPresent();
        assertThat(second.get().getSessionId()).isEqualTo(sessionId);
    }

    @Test
    void findByPlayerIdFallsBackToPostgresOnCacheMissAndRehydrates() {
        store.save(newSession());
        cache.evict(playerId);
        assertThat(cache.isCached(playerId)).isFalse();

        Optional<GameSession> loaded = store.findByPlayerId(playerId);

        assertThat(loaded).isPresent();
        assertThat(loaded.get().getSessionId()).isEqualTo(sessionId);
        assertThat(cache.isCached(playerId)).isTrue();
    }

    @Test
    void evictRemovesCacheButKeepsPostgresRecord() {
        store.save(newSession());

        store.evict(playerId);

        assertThat(cache.isCached(playerId)).isFalse();
        assertThat(repository.findBySessionId(sessionId)).isPresent();
    }

    @Test
    void staleVersionWriteRaisesOptimisticLockingFailure() {
        GameSession persisted = store.save(newSession());

        GameSession winner = repository.findById(persisted.getId()).orElseThrow();
        winner.setCurrentBet(new BigDecimal("2.0000"));
        repository.saveAndFlush(winner);

        GameSession stale = repository.findById(persisted.getId()).orElseThrow();
        // Roll the version backwards to simulate a stale read.
        stale.setSessionVersion(persisted.getSessionVersion());
        stale.setCurrentBet(new BigDecimal("3.0000"));

        assertThatThrownBy(() -> repository.saveAndFlush(stale))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }

    private GameSession newSession() {
        Instant now = Instant.now();
        return GameSession.builder()
                .sessionId(sessionId)
                .playerId(playerId)
                .gameId("aztec-fire")
                .mathVersion("v1")
                .currency("EUR")
                .currentState(GameState.BASE_GAME)
                .currentBet(new BigDecimal("1.0000"))
                .remainingFreeSpins(0)
                .accumulatedFreeSpinsWin(new BigDecimal("0.0000"))
                .activeFeaturePayload(null)
                .nextActionAllowed("SPIN")
                .sessionVersion(0L)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
