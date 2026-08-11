package com.velocity.rgs.jackpot.persistence;

import com.velocity.rgs.jackpot.domain.JackpotTier;
import com.velocity.rgs.jackpot.domain.JackpotWin;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface JackpotWinRepository extends JpaRepository<JackpotWin, String> {

    /** The exactly-once lookup: a round that has already won cannot win again. */
    Optional<JackpotWin> findByRoundId(String roundId);

    /** A player's wins, newest first, for the history page. */
    List<JackpotWin> findByPlayerIdOrderByWonAtDesc(String playerId);

    /** The most recent win of one tier, which is what the lobby ticker's "last won" line reads. */
    Optional<JackpotWin> findFirstByTierAndCurrencyOrderByWonAtDesc(JackpotTier tier, String currency);
}
