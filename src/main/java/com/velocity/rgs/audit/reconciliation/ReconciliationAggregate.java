package com.velocity.rgs.audit.reconciliation;

import java.math.BigDecimal;

/**
 * Per-player aggregate row used by {@link ReconciliationJob} to compare expected vs actual totals.
 * Each field is the SUM of the corresponding column for the bucket; {@code null} sums collapse to
 * {@link BigDecimal#ZERO} via the projection helper {@link #orZero(BigDecimal)}.
 */
public record ReconciliationAggregate(
        String playerId,
        String currency,
        BigDecimal totalAmount
) {

    public static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
