package com.velocity.rgs.wallet.api;

import com.velocity.rgs.wallet.domain.WalletTransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record WalletCreditResponse(
        String transactionId,
        WalletTransactionStatus status,
        BigDecimal balanceBefore,
        BigDecimal balanceAfter,
        String currency,
        Instant processedAt,
        boolean idempotentReplay
) {
}
