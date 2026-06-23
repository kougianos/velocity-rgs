package com.velocity.rgs.audit.reconciliation;

import com.velocity.rgs.slot.domain.FeaturePurchaseEvent;
import com.velocity.rgs.slot.domain.GameRound;
import com.velocity.rgs.wallet.domain.WalletTransaction;
import com.velocity.rgs.wallet.domain.WalletTransactionType;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Custom aggregation queries dedicated to {@link ReconciliationJob}. Kept separate from the regular
 * Spring Data repositories so the JPQL stays close to the reconciliation logic and the {@code game} /
 * {@code wallet} packages do not need to know about M6 audit concerns.
 */
@Repository
@RequiredArgsConstructor
public class ReconciliationQueryRepository {

    private final EntityManager entityManager;

    public List<ReconciliationAggregate> sumGameRoundBets(Instant bucketStart, Instant bucketEnd) {
        return entityManager.createQuery(
                "select new com.velocity.rgs.audit.reconciliation.ReconciliationAggregate(" +
                        " g.playerId, g.currency, sum(g.betAmount))" +
                        " from " + GameRound.class.getName() + " g" +
                        " where g.createdAt >= :start and g.createdAt < :end" +
                        " and g.betTransactionId is not null" +
                        " group by g.playerId, g.currency",
                ReconciliationAggregate.class)
                .setParameter("start", bucketStart)
                .setParameter("end", bucketEnd)
                .getResultList();
    }

    public List<ReconciliationAggregate> sumGameRoundWins(Instant bucketStart, Instant bucketEnd) {
        return entityManager.createQuery(
                "select new com.velocity.rgs.audit.reconciliation.ReconciliationAggregate(" +
                        " g.playerId, g.currency, sum(g.totalWin))" +
                        " from " + GameRound.class.getName() + " g" +
                        " where g.createdAt >= :start and g.createdAt < :end" +
                        " and g.winTransactionId is not null" +
                        " group by g.playerId, g.currency",
                ReconciliationAggregate.class)
                .setParameter("start", bucketStart)
                .setParameter("end", bucketEnd)
                .getResultList();
    }

    public List<ReconciliationAggregate> sumFeaturePurchaseCosts(Instant bucketStart, Instant bucketEnd) {
        return entityManager.createQuery(
                "select new com.velocity.rgs.audit.reconciliation.ReconciliationAggregate(" +
                        " f.playerId, f.currency, sum(f.cost))" +
                        " from " + FeaturePurchaseEvent.class.getName() + " f" +
                        " where f.createdAt >= :start and f.createdAt < :end" +
                        " group by f.playerId, f.currency",
                ReconciliationAggregate.class)
                .setParameter("start", bucketStart)
                .setParameter("end", bucketEnd)
                .getResultList();
    }

    public List<ReconciliationAggregate> sumWalletTransactionsByTypes(Instant bucketStart, Instant bucketEnd,
                                                                     Set<WalletTransactionType> types) {
        return entityManager.createQuery(
                "select new com.velocity.rgs.audit.reconciliation.ReconciliationAggregate(" +
                        " t.playerId, t.currency, sum(t.amount))" +
                        " from " + WalletTransaction.class.getName() + " t" +
                        " where t.createdAt >= :start and t.createdAt < :end" +
                        " and t.type in :types" +
                        " group by t.playerId, t.currency",
                ReconciliationAggregate.class)
                .setParameter("start", bucketStart)
                .setParameter("end", bucketEnd)
                .setParameter("types", types)
                .getResultList();
    }

    /**
     * Sum of rollback amounts grouped by the original transaction's type so we can subtract them
     * from BET/BONUS_BUY (debit) and WIN/FEATURE_WIN (credit) totals respectively.
     */
    public BigDecimal sumRollbackAmountsForOriginalTypes(String playerId, Instant bucketStart,
                                                         Instant bucketEnd,
                                                         Set<WalletTransactionType> originalTypes) {
        Object result = entityManager.createQuery(
                "select coalesce(sum(t.amount), 0) from " + WalletTransaction.class.getName() + " t" +
                        " where t.playerId = :playerId" +
                        " and t.createdAt >= :start and t.createdAt < :end" +
                        " and t.type = com.velocity.rgs.wallet.domain.WalletTransactionType.ROLLBACK" +
                        " and exists (select 1 from " + WalletTransaction.class.getName() + " orig" +
                        "  where orig.transactionId = t.originalTransactionId" +
                        "  and orig.type in :originalTypes)")
                .setParameter("playerId", playerId)
                .setParameter("start", bucketStart)
                .setParameter("end", bucketEnd)
                .setParameter("originalTypes", originalTypes)
                .getSingleResult();
        return result instanceof BigDecimal bd ? bd : BigDecimal.ZERO;
    }
}
