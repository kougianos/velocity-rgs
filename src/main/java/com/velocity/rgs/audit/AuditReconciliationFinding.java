package com.velocity.rgs.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Immutable row recorded by {@code ReconciliationJob} (M6 Task 6.3 / Appendix A.16) whenever a player's
 * hourly bet/win totals diverge from the wallet ledger.
 */
@Entity
@Table(name = "audit_reconciliation_finding")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditReconciliationFinding {

    public enum DiscrepancyKind {
        DEBIT_MISMATCH,
        CREDIT_MISMATCH
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "player_id", nullable = false, length = 64)
    private String playerId;

    @Column(name = "bucket_start", nullable = false)
    private Instant bucketStart;

    @Column(name = "bucket_end", nullable = false)
    private Instant bucketEnd;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "expected_debit", nullable = false, precision = 19, scale = 4)
    private BigDecimal expectedDebit;

    @Column(name = "actual_debit", nullable = false, precision = 19, scale = 4)
    private BigDecimal actualDebit;

    @Column(name = "expected_credit", nullable = false, precision = 19, scale = 4)
    private BigDecimal expectedCredit;

    @Column(name = "actual_credit", nullable = false, precision = 19, scale = 4)
    private BigDecimal actualCredit;

    @Column(name = "discrepancy", nullable = false, precision = 19, scale = 4)
    private BigDecimal discrepancy;

    @Enumerated(EnumType.STRING)
    @Column(name = "discrepancy_kind", nullable = false, length = 32)
    private DiscrepancyKind discrepancyKind;

    @Column(name = "detail", columnDefinition = "TEXT")
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
