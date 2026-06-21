package com.velocity.rgs.session.service;

import com.velocity.rgs.common.error.ErrorCode;
import com.velocity.rgs.common.error.RgsException;
import com.velocity.rgs.session.domain.GameSession;
import com.velocity.rgs.session.persistence.GameSessionRepository;
import com.velocity.rgs.session.persistence.SessionCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Façade over Postgres + Redis for session state (M4 Task 4.6 / A.10). Reads check Redis first and fall
 * back to Postgres on miss; writes go through Postgres (system of record), then refresh Redis. On any
 * disagreement Postgres wins (Concurrency Rules).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionStore {

    private final GameSessionRepository repository;
    private final SessionCache cache;

    public Optional<GameSession> findByPlayerId(String playerId) {
        Optional<GameSession> cached = cache.get(playerId);
        if (cached.isPresent()) {
            return cached;
        }
        Optional<GameSession> persisted = repository.findFirstByPlayerIdOrderByUpdatedAtDesc(playerId);
        persisted.ifPresent(cache::put);
        return persisted;
    }

    public Optional<GameSession> findBySessionId(String sessionId) {
        return repository.findBySessionId(sessionId);
    }

    public GameSession requireByPlayerId(String playerId) {
        return findByPlayerId(playerId)
                .orElseThrow(() -> new RgsException(ErrorCode.SESSION_NOT_FOUND,
                        "No active session for player: " + playerId));
    }

    public GameSession requireBySessionId(String sessionId) {
        return findBySessionId(sessionId)
                .orElseThrow(() -> new RgsException(ErrorCode.SESSION_NOT_FOUND,
                        "Session not found: " + sessionId));
    }

    @Transactional
    public GameSession save(GameSession session) {
        GameSession persisted = repository.saveAndFlush(session);
        cache.put(persisted);
        return persisted;
    }

    public void evict(String playerId) {
        cache.evict(playerId);
    }
}
