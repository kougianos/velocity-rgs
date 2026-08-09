package com.velocity.rgs.jackpot.domain;

import java.math.BigDecimal;

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
 */
public record JackpotPoolView(
        JackpotTier tier,
        String label,
        String currency,
        BigDecimal amount,
        BigDecimal seed,
        BigDecimal grownBy
) {}
