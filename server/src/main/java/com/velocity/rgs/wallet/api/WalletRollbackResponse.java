package com.velocity.rgs.wallet.api;

import com.velocity.rgs.wallet.domain.WalletTransactionStatus;

import java.time.Instant;

public record WalletRollbackResponse(
        String transactionId,
        String originalTransactionId,
        WalletTransactionStatus status,
        Instant processedAt,
        boolean idempotentReplay
) {
}
