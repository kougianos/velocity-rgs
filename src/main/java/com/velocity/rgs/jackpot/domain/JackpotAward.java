package com.velocity.rgs.jackpot.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;

/**
 * A jackpot that was just won (§2).
 *
 * @param amount what the pool held and what the player was paid
 * @param resetTo the seed the pool fell back to, so the client can explain the drop it is about to
 *                render rather than showing a figure that mysteriously collapsed
 * @param winId  the audit row, and @param txId the wallet credit - both surfaced so the claim
 *               "this was paid exactly once" is checkable from the response rather than on trust
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JackpotAward(
        JackpotTier tier,
        String label,
        String currency,
        BigDecimal amount,
        BigDecimal resetTo,
        String winId,
        String txId
) {}
