package com.velocity.rgs.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface AuditReconciliationFindingRepository extends JpaRepository<AuditReconciliationFinding, Long> {

    List<AuditReconciliationFinding> findByPlayerIdOrderByCreatedAtDesc(String playerId);

    List<AuditReconciliationFinding> findByBucketStartGreaterThanEqualOrderByBucketStartAsc(Instant since);

    boolean existsByPlayerIdAndBucketStartAndDiscrepancyKind(String playerId, Instant bucketStart,
                                                             AuditReconciliationFinding.DiscrepancyKind kind);
}
