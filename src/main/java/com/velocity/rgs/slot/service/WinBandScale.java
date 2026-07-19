package com.velocity.rgs.slot.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * The fixed ladder of win-size bands used by {@link RtpReport.Channel#winDistribution()}.
 *
 * <p>Bounds are in bet multiples and deliberately log-ish rather than linear: a slot's win distribution
 * is dominated by the 0-2x range and the interesting part - the tail that carries the volatility - lives
 * several orders of magnitude out. Linear buckets would put ~99% of rounds in the first cell and tell you
 * nothing. The ladder is shared across every game and channel so two reports are directly comparable.
 *
 * <p>The zero band is kept separate from {@code (0, 1x)}: "returned nothing" and "returned less than the
 * stake" are different player experiences, and the zero band's complement is exactly the hit frequency.
 */
final class WinBandScale {

    /** Upper bounds (exclusive) in bet multiples. The final band is open-ended. */
    private static final double[] UPPER_BOUNDS = {0.0, 1.0, 2.0, 5.0, 10.0, 50.0, 100.0, 500.0, 1000.0};

    private static final String[] LABELS = {
            "0x", "0-1x", "1-2x", "2-5x", "5-10x", "10-50x", "50-100x", "100-500x", "500-1000x", "1000x+"
    };

    static final int BAND_COUNT = LABELS.length;

    private WinBandScale() {
    }

    /**
     * Index of the band a win of {@code multiplier} bet multiples falls into. Zero (and, defensively,
     * anything negative) is band 0; every other value lands in the first band whose upper bound it is
     * strictly below, or the open-ended top band.
     */
    static int bandOf(double multiplier) {
        if (multiplier <= 0.0) {
            return 0;
        }
        for (int i = 1; i < UPPER_BOUNDS.length; i++) {
            if (multiplier < UPPER_BOUNDS[i]) {
                return i;
            }
        }
        return BAND_COUNT - 1;
    }

    /** Materialises the per-band counts into report rows, with each band's share of {@code rounds}. */
    static List<RtpReport.WinBand> toBands(long[] counts, long rounds) {
        List<RtpReport.WinBand> bands = new ArrayList<>(BAND_COUNT);
        for (int i = 0; i < BAND_COUNT; i++) {
            BigDecimal share = rounds == 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(counts[i]).multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(rounds), 4, RoundingMode.HALF_UP);
            bands.add(new RtpReport.WinBand(
                    LABELS[i],
                    BigDecimal.valueOf(i == 0 ? 0.0 : UPPER_BOUNDS[i - 1]),
                    i == BAND_COUNT - 1 ? null : BigDecimal.valueOf(UPPER_BOUNDS[i]),
                    counts[i],
                    share));
        }
        return bands;
    }
}
