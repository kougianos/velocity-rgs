package com.velocity.rgs.audit.reconciliation;

import com.velocity.rgs.audit.AuditReconciliationFinding;
import com.velocity.rgs.audit.AuditReconciliationFindingRepository;
import com.velocity.rgs.wallet.domain.WalletTransactionType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Hourly reconciliation between the game-engine view (rounds + feature purchases) and the wallet
 * ledger (M6 Task 6.3 / A.16). Runs at minute 5 of every hour by default; the cron is configurable
 * via {@code rgs.audit.reconciliation.cron}. Each player whose totals diverge gets a row in
 * {@code audit_reconciliation_finding} and increments the {@code rgs.reconciliation.discrepancy}
 * counter.
 *
 * <p>Reconciliation model:
 * <ul>
 *   <li>expected debit = sum(game_round.bet_amount where bet_transaction_id is not null) +
 *       sum(feature_purchase_event.cost)</li>
 *   <li>actual debit = sum(wallet_transaction.amount where type in BET, BONUS_BUY) minus
 *       rollbacks of those types</li>
 *   <li>expected credit = sum(game_round.total_win where win_transaction_id is not null) - Pick&Collect
 *       credits flow through {@code pick-…} synthetic round ids that this job intentionally does NOT
 *       cross-check; they are tracked via {@code pick_collect_snapshot.final_win} separately.</li>
 *   <li>actual credit = sum(wallet_transaction.amount where type in WIN, FEATURE_WIN) minus
 *       rollbacks of those types</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReconciliationJob {

    public static final String DISCREPANCY_METRIC = "rgs.reconciliation.discrepancy";

    private static final Set<WalletTransactionType> DEBIT_TYPES =
            Set.of(WalletTransactionType.BET, WalletTransactionType.BONUS_BUY);
    private static final Set<WalletTransactionType> CREDIT_TYPES =
            Set.of(WalletTransactionType.WIN, WalletTransactionType.FEATURE_WIN);

    private final ReconciliationQueryRepository queryRepository;
    private final AuditReconciliationFindingRepository findingRepository;
    private final MeterRegistry meterRegistry;

    private Counter discrepancyCounter;

    @PostConstruct
    void initMetrics() {
        this.discrepancyCounter = Counter.builder(DISCREPANCY_METRIC)
                .description("Number of reconciliation discrepancies detected between game rounds and wallet ledger")
                .register(meterRegistry);
    }

    @Scheduled(cron = "${rgs.audit.reconciliation.cron:0 5 * * * *}")
    public void runScheduled() {
        Instant now = Instant.now();
        Instant bucketEnd = now.truncatedTo(ChronoUnit.HOURS);
        Instant bucketStart = bucketEnd.minus(Duration.ofHours(1));
        runForBucket(bucketStart, bucketEnd);
    }

    /**
     * Runs reconciliation for the supplied bucket. Public so tests and ops can replay an arbitrary
     * window without waiting for the scheduler.
     */
    @Transactional
    public List<AuditReconciliationFinding> runForBucket(Instant bucketStart, Instant bucketEnd) {
        log.info("Running reconciliation for bucket {} -> {}", bucketStart, bucketEnd);

        Map<String, PlayerTotals> totals = new HashMap<>();
        collect(totals, queryRepository.sumGameRoundBets(bucketStart, bucketEnd),
                (t, v) -> t.expectedDebit = t.expectedDebit.add(v));
        collect(totals, queryRepository.sumFeaturePurchaseCosts(bucketStart, bucketEnd),
                (t, v) -> t.expectedDebit = t.expectedDebit.add(v));
        collect(totals, queryRepository.sumGameRoundWins(bucketStart, bucketEnd),
                (t, v) -> t.expectedCredit = t.expectedCredit.add(v));
        collect(totals, queryRepository.sumWalletTransactionsByTypes(bucketStart, bucketEnd, DEBIT_TYPES),
                (t, v) -> t.actualDebit = t.actualDebit.add(v));
        collect(totals, queryRepository.sumWalletTransactionsByTypes(bucketStart, bucketEnd, CREDIT_TYPES),
                (t, v) -> t.actualCredit = t.actualCredit.add(v));

        List<AuditReconciliationFinding> findings = new java.util.ArrayList<>();
        for (Map.Entry<String, PlayerTotals> entry : totals.entrySet()) {
            String playerId = entry.getKey();
            PlayerTotals t = entry.getValue();

            BigDecimal debitRollback = queryRepository.sumRollbackAmountsForOriginalTypes(
                    playerId, bucketStart, bucketEnd, DEBIT_TYPES);
            BigDecimal creditRollback = queryRepository.sumRollbackAmountsForOriginalTypes(
                    playerId, bucketStart, bucketEnd, CREDIT_TYPES);

            BigDecimal actualDebit = t.actualDebit.subtract(debitRollback);
            BigDecimal actualCredit = t.actualCredit.subtract(creditRollback);

            BigDecimal debitDelta = t.expectedDebit.subtract(actualDebit);
            BigDecimal creditDelta = t.expectedCredit.subtract(actualCredit);

            if (debitDelta.signum() != 0) {
                AuditReconciliationFinding finding = persistFinding(playerId, t.currency, bucketStart,
                        bucketEnd, t.expectedDebit, actualDebit, t.expectedCredit, actualCredit,
                        debitDelta, AuditReconciliationFinding.DiscrepancyKind.DEBIT_MISMATCH,
                        "Expected debit " + t.expectedDebit + " differs from actual " + actualDebit);
                if (finding != null) {
                    findings.add(finding);
                }
            }
            if (creditDelta.signum() != 0) {
                AuditReconciliationFinding finding = persistFinding(playerId, t.currency, bucketStart,
                        bucketEnd, t.expectedDebit, actualDebit, t.expectedCredit, actualCredit,
                        creditDelta, AuditReconciliationFinding.DiscrepancyKind.CREDIT_MISMATCH,
                        "Expected credit " + t.expectedCredit + " differs from actual " + actualCredit);
                if (finding != null) {
                    findings.add(finding);
                }
            }
        }
        log.info("Reconciliation bucket {} -> {} produced {} finding(s)",
                bucketStart, bucketEnd, findings.size());
        return findings;
    }

    private AuditReconciliationFinding persistFinding(String playerId, String currency,
                                                      Instant bucketStart, Instant bucketEnd,
                                                      BigDecimal expectedDebit, BigDecimal actualDebit,
                                                      BigDecimal expectedCredit, BigDecimal actualCredit,
                                                      BigDecimal discrepancy,
                                                      AuditReconciliationFinding.DiscrepancyKind kind,
                                                      String detail) {
        if (findingRepository.existsByPlayerIdAndBucketStartAndDiscrepancyKind(playerId, bucketStart, kind)) {
            log.debug("Skipping duplicate {} finding for player {} bucket {}", kind, playerId, bucketStart);
            return null;
        }
        AuditReconciliationFinding finding = AuditReconciliationFinding.builder()
                .playerId(playerId)
                .bucketStart(bucketStart)
                .bucketEnd(bucketEnd)
                .currency(currency)
                .expectedDebit(expectedDebit)
                .actualDebit(actualDebit)
                .expectedCredit(expectedCredit)
                .actualCredit(actualCredit)
                .discrepancy(discrepancy)
                .discrepancyKind(kind)
                .detail(detail)
                .createdAt(Instant.now())
                .build();
        AuditReconciliationFinding saved = findingRepository.save(finding);
        discrepancyCounter.increment();
        log.error("Reconciliation discrepancy player={} kind={} delta={} bucket={}",
                playerId, kind, discrepancy, bucketStart);
        return saved;
    }

    private void collect(Map<String, PlayerTotals> totals, List<ReconciliationAggregate> rows,
                         AggregateApplier applier) {
        for (ReconciliationAggregate row : rows) {
            PlayerTotals t = totals.computeIfAbsent(row.playerId(), id -> new PlayerTotals(row.currency()));
            applier.apply(t, ReconciliationAggregate.orZero(row.totalAmount()));
        }
    }

    @FunctionalInterface
    private interface AggregateApplier {
        void apply(PlayerTotals totals, BigDecimal value);
    }

    /** Mutable accumulator used inside {@link #runForBucket}. */
    private static final class PlayerTotals {
        final String currency;
        BigDecimal expectedDebit = BigDecimal.ZERO;
        BigDecimal expectedCredit = BigDecimal.ZERO;
        BigDecimal actualDebit = BigDecimal.ZERO;
        BigDecimal actualCredit = BigDecimal.ZERO;

        PlayerTotals(String currency) {
            this.currency = currency;
        }
    }

    /** Visible for tests that need an ordered iteration view of registered players. */
    Set<String> playerIds(Map<String, PlayerTotals> totals) {
        return new LinkedHashSet<>(totals.keySet());
    }
}
