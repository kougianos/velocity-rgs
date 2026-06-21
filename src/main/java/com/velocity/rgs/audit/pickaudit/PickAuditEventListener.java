package com.velocity.rgs.audit.pickaudit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Default audit sink for {@link PickAuditEvent}. Emits a structured JSON-style log line per pick so
 * the BI pipeline (or any other listener) can subscribe without changing the engine.
 */
@Slf4j
@Component
public class PickAuditEventListener {

    @EventListener
    public void onPickAudit(PickAuditEvent event) {
        log.info("pick-audit player={} session={} position={} type={} value={} " +
                        "currentCollectedBefore={} currentCollectedAfter={} " +
                        "totalFeatureWinBefore={} totalFeatureWinAfter={} " +
                        "remainingPicksBefore={} remainingPicksAfter={} completed={} " +
                        "beforeHash={} afterHash={} occurredAt={}",
                event.playerId(), event.sessionId(), event.position(),
                event.resolvedTileType(), event.resolvedValue(),
                event.currentCollectedBefore(), event.currentCollectedAfter(),
                event.totalFeatureWinBefore(), event.totalFeatureWinAfter(),
                event.remainingPicksBefore(), event.remainingPicksAfter(),
                event.featureCompleted(),
                event.beforeStateHash(), event.afterStateHash(), event.occurredAt());
    }
}
