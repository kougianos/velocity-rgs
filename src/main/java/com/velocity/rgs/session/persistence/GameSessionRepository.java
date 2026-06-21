package com.velocity.rgs.session.persistence;

import com.velocity.rgs.session.domain.GameSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface GameSessionRepository extends JpaRepository<GameSession, Long> {

    Optional<GameSession> findBySessionId(String sessionId);

    Optional<GameSession> findFirstByPlayerIdOrderByUpdatedAtDesc(String playerId);
}
