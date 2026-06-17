package com.velocity.rgs.wallet.gateway;

import com.velocity.rgs.common.error.ErrorCode;
import com.velocity.rgs.common.error.RgsException;
import com.velocity.rgs.wallet.api.WalletAuthenticateRequest;
import com.velocity.rgs.wallet.api.WalletAuthenticateResponse;
import com.velocity.rgs.wallet.api.WalletBalanceResponse;
import com.velocity.rgs.wallet.api.WalletCreditRequest;
import com.velocity.rgs.wallet.api.WalletCreditResponse;
import com.velocity.rgs.wallet.api.WalletDebitRequest;
import com.velocity.rgs.wallet.api.WalletDebitResponse;
import com.velocity.rgs.wallet.api.WalletRollbackRequest;
import com.velocity.rgs.wallet.api.WalletRollbackResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Skeleton for the external Operator Wallet API integration. Activated under the
 * {@code wallet-operator} profile only. All operations currently throw
 * {@link ErrorCode#INTERNAL_ERROR} — the full WebClient implementation is delivered
 * in Milestone 6 (Section M6.4).
 */
@Component
@Profile("wallet-operator")
public class OperatorWalletGateway implements WalletGateway {

    private static final String NOT_IMPLEMENTED =
            "OperatorWalletGateway is not implemented yet — scheduled for Milestone 6.";

    @Override
    public WalletAuthenticateResponse authenticate(WalletAuthenticateRequest request, String jwtCurrency) {
        throw new RgsException(ErrorCode.INTERNAL_ERROR, NOT_IMPLEMENTED);
    }

    @Override
    public WalletBalanceResponse balance(String playerId) {
        throw new RgsException(ErrorCode.INTERNAL_ERROR, NOT_IMPLEMENTED);
    }

    @Override
    public WalletDebitResponse debit(WalletDebitRequest request, String idempotencyKey, String jwtCurrency) {
        throw new RgsException(ErrorCode.INTERNAL_ERROR, NOT_IMPLEMENTED);
    }

    @Override
    public WalletCreditResponse credit(WalletCreditRequest request, String idempotencyKey, String jwtCurrency) {
        throw new RgsException(ErrorCode.INTERNAL_ERROR, NOT_IMPLEMENTED);
    }

    @Override
    public WalletRollbackResponse rollback(WalletRollbackRequest request, String idempotencyKey, String jwtCurrency) {
        throw new RgsException(ErrorCode.INTERNAL_ERROR, NOT_IMPLEMENTED);
    }
}
