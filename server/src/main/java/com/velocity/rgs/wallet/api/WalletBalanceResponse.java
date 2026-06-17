package com.velocity.rgs.wallet.api;

import java.math.BigDecimal;

public record WalletBalanceResponse(
        String playerId,
        BigDecimal balance,
        String currency
) {
}
