package com.velocity.rgs.jackpot.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One pool as the lobby reads it (§2).
 *
 * @param tier     the machine name, so the client keys its styling off an enum rather than parsing a label
 * @param label    the display name, so the client is not doing string surgery on an enum
 * @param amount   what the pool is worth now, at the currency's own scale
 * @param seed     the floor it returns to when won - what makes the tiers legible as a ladder
 * @param grownBy  amount minus seed: how much of this prize has actually been played for. A Mega sitting
 *                 at its seed and a Mega that has been building all week are the same number to a client
 *                 that only receives the total, and they are not the same jackpot
 * @param lastWonBy   who last took this tier, <b>masked</b>. The endpoint is anonymous, so a raw player
 *                    id here would publish who plays and when to anyone who loaded the lobby. A ticker
 *                    needs to say the prize is real and winnable; it does not need to name anyone
 * @param lastWonAt   when it last landed, so the client can render "3m ago" without a second call
 * @param lastWonAmount what it paid last time, which is the figure that makes the tier believable
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JackpotPoolView(
        JackpotTier tier,
        String label,
        String currency,
        BigDecimal amount,
        BigDecimal seed,
        BigDecimal grownBy,
        String lastWonBy,
        Instant lastWonAt,
        BigDecimal lastWonAmount
) {

    /** A pool nobody has won yet: the last-won fields are simply absent rather than empty strings. */
    public static JackpotPoolView neverWon(JackpotTier tier, String label, String currency,
                                           BigDecimal amount, BigDecimal seed, BigDecimal grownBy) {
        return new JackpotPoolView(tier, label, currency, amount, seed, grownBy, null, null, null);
    }

    /**
     * Masks a player id for public display: {@code demo-a1b2c3d4} becomes {@code demo-a1b2***}.
     *
     * <p>Enough to read as a person rather than a placeholder, and not enough to identify one. The
     * lobby's jackpot endpoint is anonymous by design, so anything shown here is shown to the internet.
     */
    public static String maskPlayer(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            return null;
        }
        String trimmed = playerId.strip();
        return trimmed.length() <= 4 ? "***" : trimmed.substring(0, Math.min(9, trimmed.length())) + "***";
    }
}
