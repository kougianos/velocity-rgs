package com.velocity.rgs.wallet.gateway;

import com.velocity.rgs.wallet.api.WalletAuthenticateRequest;
import com.velocity.rgs.wallet.api.WalletAuthenticateResponse;
import com.velocity.rgs.wallet.api.WalletBalanceResponse;
import com.velocity.rgs.wallet.api.WalletCreditRequest;
import com.velocity.rgs.wallet.api.WalletCreditResponse;
import com.velocity.rgs.wallet.api.WalletDebitRequest;
import com.velocity.rgs.wallet.api.WalletDebitResponse;
import com.velocity.rgs.wallet.api.WalletRollbackRequest;
import com.velocity.rgs.wallet.api.WalletRollbackResponse;

/**
 * RGS-facing wallet abstraction. The slot engine (M5) interacts with the wallet
 * exclusively through this contract so the underlying implementation can be swapped
 * via profile ({@code InternalWalletGateway} in POC, {@code OperatorWalletGateway}
 * in production) without touching the engine.
 */
public interface WalletGateway {

    WalletAuthenticateResponse authenticate(WalletAuthenticateRequest request, String jwtCurrency);

    WalletBalanceResponse balance(String playerId);

    WalletDebitResponse debit(WalletDebitRequest request, String idempotencyKey, String jwtCurrency);

    WalletCreditResponse credit(WalletCreditRequest request, String idempotencyKey, String jwtCurrency);

    WalletRollbackResponse rollback(WalletRollbackRequest request, String idempotencyKey, String jwtCurrency);
}
