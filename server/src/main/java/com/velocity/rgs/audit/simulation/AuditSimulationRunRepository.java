package com.velocity.rgs.audit.simulation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AuditSimulationRunRepository extends JpaRepository<AuditSimulationRun, Long> {
    Optional<AuditSimulationRun> findByRunId(String runId);
}
