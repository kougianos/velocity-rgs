package com.velocity.rgs.game.persistence;

import com.velocity.rgs.game.domain.GameRound;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GameRoundRepository extends JpaRepository<GameRound, Long> {

    Optional<GameRound> findByRoundId(String roundId);

    List<GameRound> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    List<GameRound> findByPlayerIdOrderByCreatedAtDesc(String playerId);
}
