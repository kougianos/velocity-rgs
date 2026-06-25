package com.velocity.rgs.blackjack.persistence;

import com.velocity.rgs.blackjack.domain.BlackjackRound;
import com.velocity.rgs.blackjack.domain.RoundStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BlackjackRoundRepository extends JpaRepository<BlackjackRound, Long> {

    Optional<BlackjackRound> findByRoundId(String roundId);

    List<BlackjackRound> findByPlayerIdOrderByCreatedAtDesc(String playerId);

    /** The active (unsettled) round for a session, if any - there is at most one. */
    Optional<BlackjackRound> findFirstBySessionIdAndStatusOrderByCreatedAtDesc(String sessionId, RoundStatus status);
}
