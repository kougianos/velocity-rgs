package com.velocity.rgs.rg.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Everything the player is entitled to know about their own limits, in one read.
 *
 * <p>Limits and consumption travel together on purpose. "Loss limit: 50.00" is a setting; "38.20 of
 * 50.00" is information, and it is the second that lets someone decide to stop before a limit decides
 * for them. The panel draws progress bars straight off these pairs.
 *
 * @param canPlay      whether play is available <em>at all</em>: not self-excluded, not cooling off, not
 *                     out of session time, and no limit fully consumed. Deliberately independent of any
 *                     particular stake, because the panel asks without one - a player with 2.00 of
 *                     headroom can play, even though their usual 5.00 spin will not fit in it. Whether
 *                     a <em>specific</em> stake is allowed is answered by the action list on a game
 *                     response, which knows the number, and finally by the check inside the money
 *                     transaction, which is what actually stops play
 * @param blockedBy    which rule is stopping play, or null when nothing is
 * @param blockedUntil when play resumes; null when the block has no end (self-exclusion) or none applies
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RgStatus(
        boolean enabled,
        boolean canPlay,
        RgLimitType blockedBy,
        Instant blockedUntil,
        boolean selfExcluded,

        Integer sessionLimitMinutes,
        long sessionMinutesUsed,

        BigDecimal lossLimit,
        BigDecimal netLoss,

        BigDecimal wagerLimit,
        BigDecimal wagered,

        Integer realityCheckMinutes,
        boolean realityCheckDue,
        Instant periodStartedAt,
        String currency
) {

    /** Fraction of a limit consumed, 0..1, for the panel's progress bars. Null limit means no bar. */
    public static double fraction(BigDecimal used, BigDecimal limit) {
        if (limit == null || limit.signum() <= 0) {
            return 0d;
        }
        double f = used.doubleValue() / limit.doubleValue();
        return Math.max(0d, Math.min(1d, f));
    }
}
