package com.velocity.rgs.qa.simulator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocity.rgs.audit.simulation.AuditSimulationRun;
import com.velocity.rgs.audit.simulation.AuditSimulationRunRepository;
import com.velocity.rgs.common.error.ErrorCode;
import com.velocity.rgs.common.error.RgsException;
import com.velocity.rgs.config.PlayerContext;
import com.velocity.rgs.slot.service.RtpReport;
import com.velocity.rgs.slot.service.RtpSimulationRequest;
import com.velocity.rgs.slot.service.RtpSimulationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.Instant;
import java.util.UUID;

/**
 * Synchronous RTP simulator endpoint (M7 Task 7.6 / A.19). Registered when {@code rgs.mode=demo}
 * (the default).
 *
 * <p>{@code POST /api/v1/admin/simulator/run} — ADMIN role required. Runs the requested number of spins
 * synchronously (test/QA tool: spin counts should stay under 10⁶) and persists the immutable result
 * into {@code audit_simulation_run} so the run can be cited in a compliance ticket.
 */
@Slf4j
@Controller
@RequestMapping("/api/v1/admin/simulator")
@ConditionalOnProperty(prefix = "rgs", name = "mode", havingValue = "demo", matchIfMissing = true)
@RequiredArgsConstructor
public class SimulatorAdminController {

    private static final String ADMIN_ROLE = "ADMIN";

    private final RtpSimulationService simulationService;
    private final AuditSimulationRunRepository auditRepository;
    private final PlayerContext playerContext;
    private final ObjectMapper objectMapper;

    @PostMapping("/run")
    @ResponseBody
    public ResponseEntity<RtpReport> run(@Valid @RequestBody RtpSimulationRequest request) {
        if (!playerContext.hasRole(ADMIN_ROLE)) {
            throw new RgsException(ErrorCode.FORBIDDEN_ACTION,
                    "Admin role required for simulator endpoint");
        }
        Instant started = Instant.now();
        String runId = "sim-" + UUID.randomUUID();
        RtpReport report = simulationService.run(request, runId);
        Instant finished = Instant.now();

        AuditSimulationRun row = AuditSimulationRun.builder()
                .runId(runId)
                .requestedBy(playerContext.getPlayerId())
                .gameId(request.gameId())
                .mathVersion(request.mathVersion())
                .params(toJson(request))
                .report(toJson(report))
                .startedAt(started)
                .finishedAt(finished)
                .build();
        auditRepository.saveAndFlush(row);
        log.info("Simulator run persisted runId={} elapsedMs={}", runId, report.elapsedMillis());
        return ResponseEntity.ok(report);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new RgsException(ErrorCode.INTERNAL_ERROR,
                    "Failed to serialize simulator audit payload", ex);
        }
    }
}
