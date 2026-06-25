package com.velocity.rgs.wallet.api;

import com.velocity.rgs.wallet.domain.WalletTransactionType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Wallet credit request (A.0.1). The idempotency key arrives via the
 * {@code Idempotency-Key} HTTP header (NEVER in body - A.6).
 */
public record WalletCreditRequest(
        @NotBlank String playerId,
        @NotBlank String sessionId,
        @NotBlank String roundId,
        @NotBlank String transactionId,
        @NotNull @DecimalMin(value = "0.00", inclusive = false) BigDecimal amount,
        @NotBlank String currency,
        @NotNull WalletTransactionType transactionType
) {
}
