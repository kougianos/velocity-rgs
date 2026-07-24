package com.velocity.rgs.wallet.persistence;

import com.velocity.rgs.wallet.domain.WalletTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

@Repository
public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, Long> {

    Optional<WalletTransaction> findByTransactionId(String transactionId);

    boolean existsByTransactionId(String transactionId);

    boolean existsByOriginalTransactionId(String originalTransactionId);

    /**
     * Total staked since {@code since}: every bet and bonus buy, less anything rolled back.
     *
     * <p>This is what the Responsible Gaming wager and loss limits are measured against, and it reads
     * the ledger rather than a counter kept alongside it. A counter is faster and eventually disagrees
     * with the rows an auditor would read; there is already an index on {@code (player_id, created_at)},
     * which is exactly this query's shape.
     *
     * <p>A rollback returns a stake that was taken, so it subtracts here. Counting it as staked would
     * have a failed credit push the player toward a loss limit over money they never actually risked.
     */
    @Query("""
            SELECT COALESCE(SUM(CASE
                        WHEN t.type IN (com.velocity.rgs.wallet.domain.WalletTransactionType.BET,
                                        com.velocity.rgs.wallet.domain.WalletTransactionType.BONUS_BUY)
                            THEN t.amount
                        WHEN t.type = com.velocity.rgs.wallet.domain.WalletTransactionType.ROLLBACK
                            THEN -t.amount
                        ELSE 0 END), 0)
            FROM WalletTransaction t
            WHERE t.playerId = :playerId
              AND t.status = com.velocity.rgs.wallet.domain.WalletTransactionStatus.SUCCESS
              AND t.createdAt >= :since
            """)
    BigDecimal sumStakedSince(@Param("playerId") String playerId, @Param("since") Instant since);

    /** Total paid out since {@code since} - the other half of net loss. */
    @Query("""
            SELECT COALESCE(SUM(t.amount), 0)
            FROM WalletTransaction t
            WHERE t.playerId = :playerId
              AND t.status = com.velocity.rgs.wallet.domain.WalletTransactionStatus.SUCCESS
              AND t.type IN (com.velocity.rgs.wallet.domain.WalletTransactionType.WIN,
                             com.velocity.rgs.wallet.domain.WalletTransactionType.FEATURE_WIN)
              AND t.createdAt >= :since
            """)
    BigDecimal sumWonSince(@Param("playerId") String playerId, @Param("since") Instant since);
}
