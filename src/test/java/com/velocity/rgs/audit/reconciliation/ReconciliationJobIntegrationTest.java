package com.velocity.rgs.audit.reconciliation;

import com.velocity.rgs.audit.AuditReconciliationFinding;
import com.velocity.rgs.audit.AuditReconciliationFindingRepository;
import com.velocity.rgs.slot.domain.GameRound;
import com.velocity.rgs.slot.persistence.FeaturePurchaseEventRepository;
import com.velocity.rgs.slot.persistence.GameRoundRepository;
import com.velocity.rgs.session.domain.GameState;
import com.velocity.rgs.testsupport.RgsIntegrationTest;
import com.velocity.rgs.wallet.domain.WalletTransaction;
import com.velocity.rgs.wallet.domain.WalletTransactionStatus;
import com.velocity.rgs.wallet.domain.WalletTransactionType;
import com.velocity.rgs.wallet.persistence.WalletBalanceRepository;
import com.velocity.rgs.wallet.persistence.WalletTransactionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@RgsIntegrationTest
class ReconciliationJobIntegrationTest {

    @Autowired private ReconciliationJob reconciliationJob;
    @Autowired private AuditReconciliationFindingRepository findingRepository;
    @Autowired private GameRoundRepository roundRepository;
    @Autowired private WalletTransactionRepository walletTransactionRepository;
    @Autowired private WalletBalanceRepository walletBalanceRepository;
    @Autowired private FeaturePurchaseEventRepository featurePurchaseRepository;
    @Autowired private MeterRegistry meterRegistry;

    @BeforeEach
    void clean() {
        findingRepository.deleteAll();
        roundRepository.deleteAll();
        featurePurchaseRepository.deleteAll();
        walletTransactionRepository.deleteAll();
        walletBalanceRepository.deleteAll();
    }

    @Test
    void detectsDebitMismatchAndIncrementsCounter() {
        Instant bucketEnd = Instant.now().truncatedTo(ChronoUnit.HOURS);
        Instant bucketStart = bucketEnd.minus(Duration.ofHours(1));
        Instant inBucket = bucketStart.plus(Duration.ofMinutes(10));
        String playerId = "p-recon-debit";

        // Game round expects a 5.00 debit
        seedRound(playerId, "rnd-debit-1", inBucket, new BigDecimal("5.00"), BigDecimal.ZERO, "bet-debit-1", null);
        // Wallet only recorded 3.00 → 2.00 discrepancy
        seedWalletTx(playerId, "bet-debit-1", WalletTransactionType.BET, new BigDecimal("3.00"), inBucket, null);

        double before = counterCount();
        List<AuditReconciliationFinding> findings = reconciliationJob.runForBucket(bucketStart, bucketEnd);

        assertThat(findings).hasSize(1);
        AuditReconciliationFinding f = findings.get(0);
        assertThat(f.getPlayerId()).isEqualTo(playerId);
        assertThat(f.getDiscrepancyKind()).isEqualTo(AuditReconciliationFinding.DiscrepancyKind.DEBIT_MISMATCH);
        assertThat(f.getDiscrepancy()).isEqualByComparingTo("2.00");
        assertThat(counterCount() - before).isEqualTo(1.0);
    }

    @Test
    void detectsCreditMismatch() {
        Instant bucketEnd = Instant.now().truncatedTo(ChronoUnit.HOURS);
        Instant bucketStart = bucketEnd.minus(Duration.ofHours(1));
        Instant inBucket = bucketStart.plus(Duration.ofMinutes(15));
        String playerId = "p-recon-credit";

        seedRound(playerId, "rnd-credit-1", inBucket, new BigDecimal("1.00"), new BigDecimal("4.00"),
                "bet-credit-1", "win-credit-1");
        seedWalletTx(playerId, "bet-credit-1", WalletTransactionType.BET, new BigDecimal("1.00"), inBucket, null);
        // Wallet only credited 2.50 instead of 4.00
        seedWalletTx(playerId, "win-credit-1", WalletTransactionType.WIN, new BigDecimal("2.50"), inBucket, null);

        List<AuditReconciliationFinding> findings = reconciliationJob.runForBucket(bucketStart, bucketEnd);
        assertThat(findings)
                .extracting(AuditReconciliationFinding::getDiscrepancyKind)
                .containsExactly(AuditReconciliationFinding.DiscrepancyKind.CREDIT_MISMATCH);
        assertThat(findings.get(0).getDiscrepancy()).isEqualByComparingTo("1.50");
    }

    @Test
    void reconciledPlayerProducesNoFindings() {
        Instant bucketEnd = Instant.now().truncatedTo(ChronoUnit.HOURS);
        Instant bucketStart = bucketEnd.minus(Duration.ofHours(1));
        Instant inBucket = bucketStart.plus(Duration.ofMinutes(20));
        String playerId = "p-recon-clean";

        seedRound(playerId, "rnd-clean-1", inBucket, new BigDecimal("2.00"), new BigDecimal("5.00"),
                "bet-clean-1", "win-clean-1");
        seedWalletTx(playerId, "bet-clean-1", WalletTransactionType.BET, new BigDecimal("2.00"), inBucket, null);
        seedWalletTx(playerId, "win-clean-1", WalletTransactionType.WIN, new BigDecimal("5.00"), inBucket, null);

        assertThat(reconciliationJob.runForBucket(bucketStart, bucketEnd)).isEmpty();
    }

    @Test
    void rollbackOffsetsActualDebitAndProducesNoFalsePositive() {
        Instant bucketEnd = Instant.now().truncatedTo(ChronoUnit.HOURS);
        Instant bucketStart = bucketEnd.minus(Duration.ofHours(1));
        Instant inBucket = bucketStart.plus(Duration.ofMinutes(25));
        String playerId = "p-recon-rb";

        // No bet on the round side (treat the round as never recorded — i.e. rolled back)
        seedWalletTx(playerId, "bet-rb-1", WalletTransactionType.BET, new BigDecimal("3.00"), inBucket, null);
        // Rollback compensates the BET entirely
        seedWalletTx(playerId, "bet-rb-1:rb", WalletTransactionType.ROLLBACK, new BigDecimal("3.00"), inBucket,
                "bet-rb-1");

        assertThat(reconciliationJob.runForBucket(bucketStart, bucketEnd)).isEmpty();
    }

    @Test
    void idempotentReRunDoesNotDuplicateFinding() {
        Instant bucketEnd = Instant.now().truncatedTo(ChronoUnit.HOURS);
        Instant bucketStart = bucketEnd.minus(Duration.ofHours(1));
        Instant inBucket = bucketStart.plus(Duration.ofMinutes(30));
        String playerId = "p-recon-idem";

        seedRound(playerId, "rnd-idem-1", inBucket, new BigDecimal("4.00"), BigDecimal.ZERO, "bet-idem-1", null);
        // No wallet row at all → full mismatch

        assertThat(reconciliationJob.runForBucket(bucketStart, bucketEnd)).hasSize(1);
        // Second run must not insert a duplicate row (UNIQUE on player+bucket+kind)
        assertThat(reconciliationJob.runForBucket(bucketStart, bucketEnd)).isEmpty();
        assertThat(findingRepository.findByPlayerIdOrderByCreatedAtDesc(playerId)).hasSize(1);
    }

    // ----------------------------------------------------------------- helpers
    private void seedRound(String playerId, String roundId, Instant createdAt, BigDecimal bet,
                           BigDecimal win, String betTxId, String winTxId) {
        GameRound round = GameRound.builder()
                .sessionId("ses-" + playerId)
                .playerId(playerId)
                .roundId(roundId)
                .gameId("aztec-fire")
                .mathVersion("1.0.0")
                .stateContext(GameState.BASE_GAME)
                .betAmount(bet)
                .betAmountMinor(bet.movePointRight(2).longValue())
                .totalWin(win)
                .totalWinMinor(win.movePointRight(2).longValue())
                .currency("EUR")
                .matrix("[[1,2,3],[4,5,6],[7,8,9],[1,2,3],[4,5,6]]")
                .stopPositions("[0,0,0,0,0]")
                .rngDraws("[]")
                .powerBetActive(false)
                .betTransactionId(betTxId)
                .winTransactionId(winTxId)
                .createdAt(createdAt)
                .build();
        roundRepository.saveAndFlush(round);
    }

    private void seedWalletTx(String playerId, String txId, WalletTransactionType type, BigDecimal amount,
                              Instant createdAt, String originalTxId) {
        WalletTransaction tx = WalletTransaction.builder()
                .playerId(playerId)
                .transactionId(txId)
                .originalTransactionId(originalTxId)
                .sessionId("ses-" + playerId)
                .roundId("rnd-x")
                .type(type)
                .status(WalletTransactionStatus.SUCCESS)
                .amount(amount)
                .amountMinor(amount.movePointRight(2).longValue())
                .currency("EUR")
                .balanceBefore(new BigDecimal("100.00"))
                .balanceAfter(new BigDecimal("100.00"))
                .createdAt(createdAt)
                .build();
        walletTransactionRepository.saveAndFlush(tx);
    }

    private double counterCount() {
        var counter = meterRegistry.find(ReconciliationJob.DISCREPANCY_METRIC).counter();
        return counter == null ? 0.0 : counter.count();
    }
}
