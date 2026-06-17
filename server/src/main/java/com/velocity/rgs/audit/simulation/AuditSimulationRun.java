package com.velocity.rgs.audit.simulation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Audit record for one RTP simulator invocation (M7 Task 7.6 / A.19). Persisted on completion of
 * {@code POST /api/v1/admin/simulator/run} so compliance can later reproduce the report from the
 * canonical params + game/math fingerprint.
 */
@Entity
@Table(name = "audit_simulation_run")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditSimulationRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "run_id", nullable = false, unique = true, length = 64)
    private String runId;

    @Column(name = "requested_by", nullable = false, length = 64)
    private String requestedBy;

    @Column(name = "game_id", nullable = false, length = 64)
    private String gameId;

    @Column(name = "math_version", nullable = false, length = 32)
    private String mathVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "params", nullable = false, columnDefinition = "jsonb")
    private String params;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "report", nullable = false, columnDefinition = "jsonb")
    private String report;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at", nullable = false)
    private Instant finishedAt;
}
