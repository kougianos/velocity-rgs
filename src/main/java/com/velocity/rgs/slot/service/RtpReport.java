package com.velocity.rgs.slot.service;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Output of an {@link RtpSimulationService} invocation per A.19. Each {@link Channel} reports the
 * effective bet/win totals and the resulting RTP percentage (HALF_UP, 4dp). The {@code overall} block
 * is the wager-weighted aggregate across all non-zero channels.
 *
 * <p>Beyond RTP a channel also carries the two shape statistics a player actually reads on the game's
 * spec sheet: {@code hitFrequencyPercent} (what share of rounds return anything at all) and the
 * {@code winDistribution} band histogram whose tail is bounded by {@code maxWinMultiplier}. RTP alone
 * says nothing about either - two games can converge to the same 96% with wildly different volatility -
 * so these are what make the declared "Hit Frequency" and "Max multiplier" numbers verifiable.
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
        long pickEntries,
        long respinEntries
) {

    /**
     * One sampled channel.
     *
     * @param spins               rounds played
     * @param hits                rounds that returned a win &gt; 0
     * @param hitFrequencyPercent {@code hits / spins * 100}, HALF_UP 4dp
     * @param maxWinMultiplier    largest single-round win expressed in bet multiples; bounded by
     *                            {@code limits.maxWinPerRoundMultiplier}
     * @param winDistribution     ordered histogram of round wins in bet multiples
     */
    @Builder
    public record Channel(
            long spins,
            BigDecimal totalBet,
            BigDecimal totalWin,
            BigDecimal rtpPercent,
            long hits,
            BigDecimal hitFrequencyPercent,
            BigDecimal maxWinMultiplier,
            List<WinBand> winDistribution
    ) {

        public Channel {
            winDistribution = winDistribution == null ? List.of() : List.copyOf(winDistribution);
        }
    }

    /**
     * One bucket of the win-size histogram. A round lands in the band whose
     * {@code [lowerInclusive, upperExclusive)} interval contains its win in bet multiples; the top band
     * is open-ended ({@code upperExclusive == null}).
     *
     * @param sharePercent share of the channel's rounds that landed in this band, HALF_UP 4dp
     */
    public record WinBand(
            String label,
            BigDecimal lowerInclusive,
            BigDecimal upperExclusive,
            long count,
            BigDecimal sharePercent
    ) {}
}
