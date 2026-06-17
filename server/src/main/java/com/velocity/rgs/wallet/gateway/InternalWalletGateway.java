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
import com.velocity.rgs.wallet.service.InternalWalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * In-process implementation of the wallet gateway. Active under {@code default},
 * {@code demo}, {@code wallet-internal}, and {@code test} profiles (everything
 * except {@code wallet-operator}).
 */
@Component
@Profile({"default", "demo", "wallet-internal", "test", "simulator"})
@RequiredArgsConstructor
public class InternalWalletGateway implements WalletGateway {

    private final InternalWalletService walletService;

    @Override
    public WalletAuthenticateResponse authenticate(WalletAuthenticateRequest request, String jwtCurrency) {
        return walletService.authenticate(request, jwtCurrency);
    }

    @Override
    public WalletBalanceResponse balance(String playerId) {
        return walletService.balance(playerId);
    }

    @Override
    public WalletDebitResponse debit(WalletDebitRequest request, String idempotencyKey, String jwtCurrency) {
        return walletService.debit(request, idempotencyKey, jwtCurrency);
    }

    @Override
    public WalletCreditResponse credit(WalletCreditRequest request, String idempotencyKey, String jwtCurrency) {
        return walletService.credit(request, idempotencyKey, jwtCurrency);
    }

    @Override
    public WalletRollbackResponse rollback(WalletRollbackRequest request, String idempotencyKey, String jwtCurrency) {
        return walletService.rollback(request, idempotencyKey, jwtCurrency);
    }
}
