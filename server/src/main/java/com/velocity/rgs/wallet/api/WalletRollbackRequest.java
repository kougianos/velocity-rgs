package com.velocity.rgs.wallet.api;

import com.velocity.rgs.wallet.domain.RollbackReason;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Wallet rollback request (A.0.1). The idempotency key arrives via the
 * {@code Idempotency-Key} HTTP header (NEVER in body — A.6).
 */
public record WalletRollbackRequest(
        @NotBlank String playerId,
        @NotBlank String originalTransactionId,
        @NotBlank String transactionId,
        @NotNull RollbackReason rollbackReason
) {
}
