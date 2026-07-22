package com.velocity.rgs.slot.feature.freespins;

import com.velocity.rgs.slot.feature.freespins.FreeSpinsSettlementCodec.FreeSpinsSettlement;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * These are money terms, written by the spin loop and read back by a replay that has to land on the
 * same figure to the minor unit - so the round trip has to be exact, not close.
 */
class FreeSpinsSettlementCodecTest {

    private final FreeSpinsSettlementCodec codec = new FreeSpinsSettlementCodec();

    @Test
    void roundTripsTheTermsWithoutLosingPrecision() {
        FreeSpinsSettlement settlement =
                new FreeSpinsSettlement(new BigDecimal("16.0800"), new BigDecimal("2.55"), true);

        FreeSpinsSettlement back = codec.decode(codec.encode(settlement));

        assertThat(back.accumulatedWinBefore()).isEqualByComparingTo("16.0800");
        assertThat(back.buyMultiplier()).isEqualByComparingTo("2.55");
        assertThat(back.settled()).isTrue();
    }

    /** The sum the whole thing exists to make reproducible. */
    @Test
    void rebuildsThePayoutFromTheRunningTotalAndTheBoost() {
        FreeSpinsSettlement settlement =
                new FreeSpinsSettlement(new BigDecimal("16.08"), new BigDecimal("2.55"), true);

        assertThat(settlement.payoutFor(new BigDecimal("4.16"))).isEqualByComparingTo("51.6120");
    }

    /** An organically triggered feature carries no buy marker, so it settles at face value. */
    @Test
    void anUnboostedFeaturePaysWhatItAccumulated() {
        FreeSpinsSettlement settlement =
                new FreeSpinsSettlement(new BigDecimal("9.00"), BigDecimal.ONE, true);

        assertThat(settlement.payoutFor(new BigDecimal("1.50"))).isEqualByComparingTo("10.50");
    }

    /**
     * Absent and unreadable both decode to null rather than to a zero-accumulator default, because the
     * settlement path refuses on null and would otherwise silently verify a payout against a guess.
     */
    @Test
    void readsAnythingItCannotUnderstandAsAbsent() {
        assertThat(codec.decode(null)).isNull();
        assertThat(codec.decode(Map.of())).isNull();
        assertThat(codec.decode(Map.of("stickyWilds", java.util.List.of()))).isNull();
        assertThat(codec.decode(Map.of(FreeSpinsSettlementCodec.KEY, "not a block"))).isNull();
        assertThat(codec.decode(Map.of(FreeSpinsSettlementCodec.KEY, Map.of("settled", true)))).isNull();
    }

    /** The two blocks share one {@code feature_context}, so neither may swallow the other. */
    @Test
    void coexistsWithTheWildCarryBlockInOneContext() {
        Map<String, Object> context = new java.util.LinkedHashMap<>();
        context.put("stickyWilds", java.util.List.of(Map.of("row", 1, "col", 2, "remainingSpins", 3)));
        context.putAll(codec.encode(
                new FreeSpinsSettlement(new BigDecimal("2.00"), BigDecimal.ONE, false)));

        assertThat(codec.decode(context)).isNotNull();
        assertThat(context).containsKey("stickyWilds");
    }
}
