package com.velocity.rgs.audit.pickaudit;

import com.velocity.rgs.math.domain.PickTileType;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Application event published by {@code SlotEngineService.pickFeature} after every Pick &amp; Collect
 * interaction (M6 Task 6.5 / Section 5 Implementation Notes). Carries deterministic before/after state
 * hashes so a downstream auditor (or BI sink) can prove that the engine resolved the pick exactly as
 * the snapshot records.
 */
public record PickAuditEvent(
        String playerId,
        String sessionId,
        int position,
        PickTileType resolvedTileType,
        BigDecimal resolvedValue,
        String beforeStateHash,
        String afterStateHash,
        BigDecimal currentCollectedBefore,
        BigDecimal currentCollectedAfter,
        BigDecimal totalFeatureWinBefore,
        BigDecimal totalFeatureWinAfter,
        int remainingPicksBefore,
        int remainingPicksAfter,
        boolean featureCompleted,
        Instant occurredAt
) {
}
