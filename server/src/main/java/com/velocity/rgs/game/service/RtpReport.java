package com.velocity.rgs.game.service;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Output of an {@link RtpSimulationService} invocation per A.19. Each {@link Channel} reports the
 * effective bet/win totals and the resulting RTP percentage (HALF_UP, 4dp). The {@code overall} block
 * is the wager-weighted aggregate across all non-zero channels.
 */
@Builder
public record RtpReport(
        String runId,
        String gameId,
        String mathVersion,
        BigDecimal bet,
        Map<String, Channel> channels,
        Channel overall,
        long elapsedMillis,
        Instant generatedAt,
        long freeSpinTriggers,
        long pickEntries
) {

    @Builder
    public record Channel(
            long spins,
            BigDecimal totalBet,
            BigDecimal totalWin,
            BigDecimal rtpPercent
    ) {}
}
