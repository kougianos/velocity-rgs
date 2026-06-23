package com.velocity.rgs.roulette.persistence;

import com.velocity.rgs.roulette.domain.RouletteRound;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RouletteRoundRepository extends JpaRepository<RouletteRound, Long> {

    Optional<RouletteRound> findByRoundId(String roundId);

    List<RouletteRound> findByPlayerIdOrderByCreatedAtDesc(String playerId);
}
