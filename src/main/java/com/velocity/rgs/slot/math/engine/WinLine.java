package com.velocity.rgs.slot.math.engine;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;

/**
 * Single winning line per A.7. Field names match the wire format exactly.
 *
 * <p>The shape covers both win models. Under {@link com.velocity.rgs.slot.math.domain.WinModel#PAYLINES}
 * a win belongs to exactly one configured line, so {@code lineId} identifies it and {@code ways} is 1 -
 * which is what every game shipped so far emits. Under
 * {@link com.velocity.rgs.slot.math.domain.WinModel#WAYS} there are no configured lines: wins are
 * aggregated per (symbol, count), {@code lineId} is {@code null}, and {@code ways} carries how many of
 * the grid's paths formed the run.
 *
 * <p>Prefer the factories over the canonical constructor - they name the model at the call site and keep
 * the {@code lineId}/{@code ways} pairing honest.
 *
 * @param lineId      configured payline id; {@code null} for a ways win
 * @param symbolId    the symbol that formed the run
 * @param count       how many reels the run covers, from whichever end anchored it
 * @param ways        contributing paths; always 1 for a payline win
 * @param payout      already rounded to currency scale by the evaluator
 * @param rightToLeft {@code TRUE} only for the mirrored half of a
 *                    {@link com.velocity.rgs.slot.math.domain.WaysDirection#BOTH_WAYS} win, whose run
 *                    covers the <em>rightmost</em> {@code count} reels. Null (and omitted from JSON)
 *                    otherwise, so payline games and left-to-right ways games are byte-identical to
 *                    before win-both-ways existed
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WinLine(Integer lineId, int symbolId, int count, int ways, BigDecimal payout,
                      Boolean rightToLeft) {

    public WinLine {
        if (count < 0) {
            throw new IllegalArgumentException("count must be >= 0");
        }
        if (ways < 1) {
            throw new IllegalArgumentException("ways must be >= 1, found " + ways);
        }
    }

    /** Backwards-compatible constructor for the overwhelmingly common left-to-right case. */
    public WinLine(Integer lineId, int symbolId, int count, int ways, BigDecimal payout) {
        this(lineId, symbolId, count, ways, payout, null);
    }

    /** A win on a configured payline. */
    public static WinLine payline(int lineId, int symbolId, int count, BigDecimal payout) {
        return new WinLine(lineId, symbolId, count, 1, payout, null);
    }

    /** A ways win, aggregated across every path that formed the same (symbol, count) run. */
    public static WinLine ways(int symbolId, int count, int ways, BigDecimal payout) {
        return new WinLine(null, symbolId, count, ways, payout, null);
    }

    /** A ways win anchored on the rightmost reel - the mirrored half of a win-both-ways game. */
    public static WinLine waysRightToLeft(int symbolId, int count, int ways, BigDecimal payout) {
        return new WinLine(null, symbolId, count, ways, payout, Boolean.TRUE);
    }

    /** True when this win's run is anchored on the rightmost reel rather than reel 0. */
    public boolean isRightToLeft() {
        return Boolean.TRUE.equals(rightToLeft);
    }
}
