package com.velocity.rgs.wallet.api;

import java.math.BigDecimal;

public record WalletAuthenticateResponse(
        String playerId,
        String currency,
        BigDecimal balance,
        boolean eligible
) {
}
