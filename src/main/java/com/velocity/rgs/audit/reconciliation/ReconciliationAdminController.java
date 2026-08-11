package com.velocity.rgs.audit.reconciliation;

import com.velocity.rgs.audit.AuditReconciliationFinding;
import com.velocity.rgs.audit.AuditReconciliationFindingRepository;
import com.velocity.rgs.common.error.ErrorCode;
import com.velocity.rgs.common.error.RgsException;
import com.velocity.rgs.config.PlayerContext;
import com.velocity.rgs.wallet.api.WalletCreditRequest;
import com.velocity.rgs.wallet.domain.WalletTransactionType;
import com.velocity.rgs.wallet.gateway.WalletGateway;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Reconciliation findings, on demand (§2).
 *
 * <p>The job runs hourly and writes rows nobody looks at. This surfaces them, and lets an operator run
 * a window without waiting for the cron - which is also what makes the property demonstrable: a
 * jackpot can be won and reconciled while somebody watches, rather than an hour later.
 *
 * <p>{@link #seedUnexplainedCredit} exists because <b>an empty findings report and a broken findings
 * report look identical</b>. Credits money with no round, no feature purchase and no jackpot row behind
 * it, so the next run has something it must catch. Demo mode only, and the endpoint says what it is:
 * the point is to prove the report works, not to hide that the discrepancy was planted.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/reconciliation")
@RequiredArgsConstructor
public class ReconciliationAdminController {

    private static final String ADMIN_ROLE = "ADMIN";

    private final ReconciliationJob reconciliationJob;
    private final AuditReconciliationFindingRepository findingRepository;
    private final PlayerContext playerContext;
    private final WalletGateway walletGateway;

    /** Findings from the last {@code hours} of buckets, newest first. */
    @GetMapping("/findings")
    public ResponseEntity<List<FindingView>> findings(
            @RequestParam(defaultValue = "24") int hours) {
        requireAdmin();
        // A minute of slack on the lower bound. A run over the same number of hours stamps its
        // findings at bucketStart = now - hours, and this query computes its own "now" a moment later -
        // so without the margin the list would exclude the very findings the run just wrote.
        Instant since = Instant.now().minus(Math.max(1, hours), ChronoUnit.HOURS)
                .minus(1, ChronoUnit.MINUTES);
        List<FindingView> views = findingRepository
                .findByBucketStartGreaterThanEqualOrderByBucketStartAsc(since).stream()
                .sorted(Comparator.comparing(AuditReconciliationFinding::getBucketStart).reversed())
                .map(FindingView::of)
                .toList();
        return ResponseEntity.ok(views);
    }

    /**
     * Runs reconciliation over a window now.
     *
     * <p>Defaults to the last hour, which is the same window the scheduler would cover, so what an
     * operator sees here is what the cron would have written.
     */
    @PostMapping("/run")
    public ResponseEntity<RunResponse> run(@RequestParam(defaultValue = "1") int hours) {
        requireAdmin();
        Instant end = Instant.now();
        Instant start = end.minus(Math.max(1, hours), ChronoUnit.HOURS);
        List<AuditReconciliationFinding> found = reconciliationJob.runForBucket(start, end);
        log.info("ADMIN reconciliation run admin={} window={}h findings={}",
                playerContext.getPlayerId(), hours, found.size());
        return ResponseEntity.ok(new RunResponse(start, end, found.size(),
                found.stream().map(FindingView::of).toList()));
    }

    /**
     * Plants a credit nothing can explain, so the next run has something to catch. Demo mode only.
     */
    @ConditionalOnProperty(prefix = "rgs", name = "mode", havingValue = "demo", matchIfMissing = true)
    @PostMapping("/seed-unexplained-credit")
    public ResponseEntity<SeedResponse> seedUnexplainedCredit(
            @Valid @RequestBody SeedRequest request) {
        requireAdmin();
        String txId = "unexplained-" + UUID.randomUUID();
        walletGateway.credit(new WalletCreditRequest(
                        request.playerId(),
                        "seeded-session",
                        // A round id that does not exist, which is the whole point: the ledger will
                        // hold a credit the game view cannot account for.
                        "round-that-does-not-exist-" + UUID.randomUUID(),
                        txId,
                        request.amount(),
                        request.currency(),
                        WalletTransactionType.WIN),
                txId, request.currency());
        log.info("ADMIN seeded unexplained credit admin={} player={} amount={} {}",
                playerContext.getPlayerId(), request.playerId(), request.amount(), request.currency());
        return ResponseEntity.ok(new SeedResponse(txId, request.amount(), request.currency(),
                "Credited with no round behind it. Run reconciliation and it will be flagged."));
    }

    private void requireAdmin() {
        if (!playerContext.hasRole(ADMIN_ROLE)) {
            throw new RgsException(ErrorCode.FORBIDDEN_ACTION,
                    "Admin role required for reconciliation endpoints");
        }
    }

    // ---------------------------------------------------------------- DTOs

    public record SeedRequest(
            @NotBlank String playerId,
            @NotBlank String currency,
            @Positive BigDecimal amount
    ) {}

    public record SeedResponse(String transactionId, BigDecimal amount, String currency, String message) {}

    public record RunResponse(Instant bucketStart, Instant bucketEnd, int findings,
                              List<FindingView> detail) {}

    public record FindingView(
            Long id,
            String playerId,
            String currency,
            Instant bucketStart,
            Instant bucketEnd,
            String kind,
            BigDecimal expectedDebit,
            BigDecimal actualDebit,
            BigDecimal expectedCredit,
            BigDecimal actualCredit,
            BigDecimal discrepancy,
            String detail
    ) {
        static FindingView of(AuditReconciliationFinding f) {
            return new FindingView(f.getId(), f.getPlayerId(), f.getCurrency(),
                    f.getBucketStart(), f.getBucketEnd(), f.getDiscrepancyKind().name(),
                    f.getExpectedDebit(), f.getActualDebit(),
                    f.getExpectedCredit(), f.getActualCredit(),
                    f.getDiscrepancy(), f.getDetail());
        }
    }
}
