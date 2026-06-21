package com.velocity.rgs.wallet.service;

import com.velocity.rgs.common.error.ErrorCode;
import com.velocity.rgs.common.error.RgsException;
import com.velocity.rgs.common.money.Money;
import com.velocity.rgs.wallet.api.WalletAuthenticateRequest;
import com.velocity.rgs.wallet.api.WalletAuthenticateResponse;
import com.velocity.rgs.wallet.api.WalletBalanceResponse;
import com.velocity.rgs.wallet.api.WalletCreditRequest;
import com.velocity.rgs.wallet.api.WalletCreditResponse;
import com.velocity.rgs.wallet.api.WalletDebitRequest;
import com.velocity.rgs.wallet.api.WalletDebitResponse;
import com.velocity.rgs.wallet.api.WalletRollbackRequest;
import com.velocity.rgs.wallet.api.WalletRollbackResponse;
import com.velocity.rgs.wallet.domain.WalletBalance;
import com.velocity.rgs.wallet.domain.WalletTransaction;
import com.velocity.rgs.wallet.domain.WalletTransactionType;
import com.velocity.rgs.wallet.persistence.WalletBalanceRepository;
import com.velocity.rgs.wallet.persistence.WalletTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.function.Supplier;

/**
 * In-process wallet implementation (M3). Active under {@code demo}, {@code default},
 * {@code wallet-internal}, and {@code test} profiles via
 * {@link com.velocity.rgs.wallet.gateway.InternalWalletGateway}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InternalWalletService {

    /** Retry budget for optimistic-lock contention on the balance row. */
    public static final int MAX_OPTIMISTIC_RETRIES = 4;

    private final WalletBalanceRepository balanceRepository;
    private final WalletTransactionRepository transactionRepository;
    private final WalletLedgerWriter ledgerWriter;
    private final ObjectProvider<WalletDemoSeeder> demoSeederProvider;

    // ------------------------------------------------------------------ authenticate

    @Transactional
    public WalletAuthenticateResponse authenticate(WalletAuthenticateRequest request, String jwtCurrency) {
        WalletDemoSeeder seeder = demoSeederProvider.getIfAvailable();
        WalletBalance balance = seeder != null
                ? seeder.seedIfAbsent(request.playerId(), jwtCurrency)
                : balanceRepository.findById(request.playerId())
                        .orElseThrow(() -> new RgsException(ErrorCode.AUTH_FAILED,
                                "Unknown player: " + request.playerId()));

        if (jwtCurrency != null && !jwtCurrency.equals(balance.getCurrency())) {
            throw new RgsException(ErrorCode.CURRENCY_MISMATCH,
                    "JWT currency '" + jwtCurrency + "' does not match wallet currency '"
                            + balance.getCurrency() + "'");
        }
        Money m = Money.fromMinor(balance.getBalanceMinor(), balance.getCurrency());
        return new WalletAuthenticateResponse(balance.getPlayerId(), balance.getCurrency(), m.amount(), true);
    }

    // ------------------------------------------------------------------ balance

    @Transactional(readOnly = true)
    public WalletBalanceResponse balance(String playerId) {
        WalletBalance row = balanceRepository.findById(playerId)
                .orElseThrow(() -> new RgsException(ErrorCode.AUTH_FAILED,
                        "Unknown player: " + playerId));
        Money m = Money.fromMinor(row.getBalanceMinor(), row.getCurrency());
        return new WalletBalanceResponse(row.getPlayerId(), m.amount(), row.getCurrency());
    }

    // ------------------------------------------------------------------ debit

    public WalletDebitResponse debit(WalletDebitRequest req, String idempotencyKey, String jwtCurrency) {
        validatePlayerCurrency(req.currency(), jwtCurrency);
        Money amount = toMoney(req.amount(), req.currency());

        if (transactionRepository.existsByTransactionId(req.transactionId())) {
            throw new RgsException(ErrorCode.DUPLICATE_TRANSACTION,
                    "Duplicate wallet transactionId: " + req.transactionId());
        }

        return withRetry(() -> ledgerWriter.debit(req, idempotencyKey, amount));
    }

    // ------------------------------------------------------------------ credit

    public WalletCreditResponse credit(WalletCreditRequest req, String idempotencyKey, String jwtCurrency) {
        validatePlayerCurrency(req.currency(), jwtCurrency);
        Money amount = toMoney(req.amount(), req.currency());

        if (transactionRepository.existsByTransactionId(req.transactionId())) {
            throw new RgsException(ErrorCode.DUPLICATE_TRANSACTION,
                    "Duplicate wallet transactionId: " + req.transactionId());
        }

        return withRetry(() -> ledgerWriter.credit(req, idempotencyKey, amount));
    }

    // ------------------------------------------------------------------ rollback

    public WalletRollbackResponse rollback(WalletRollbackRequest req, String idempotencyKey, String jwtCurrency) {
        if (transactionRepository.existsByTransactionId(req.transactionId())) {
            throw new RgsException(ErrorCode.DUPLICATE_TRANSACTION,
                    "Duplicate wallet transactionId: " + req.transactionId());
        }

        WalletTransaction original = transactionRepository.findByTransactionId(req.originalTransactionId())
                .orElseThrow(() -> new RgsException(ErrorCode.ORIGINAL_TRANSACTION_NOT_FOUND,
                        "Original transaction not found: " + req.originalTransactionId()));

        if (original.getType() == WalletTransactionType.ROLLBACK) {
            throw new RgsException(ErrorCode.VALIDATION_ERROR,
                    "Cannot rollback a rollback transaction");
        }
        if (!original.getPlayerId().equals(req.playerId())) {
            throw new RgsException(ErrorCode.FORBIDDEN_ACTION,
                    "Player mismatch on rollback");
        }
        if (jwtCurrency != null && !jwtCurrency.equals(original.getCurrency())) {
            throw new RgsException(ErrorCode.CURRENCY_MISMATCH,
                    "JWT currency '" + jwtCurrency + "' does not match transaction currency '"
                            + original.getCurrency() + "'");
        }
        if (transactionRepository.existsByOriginalTransactionId(req.originalTransactionId())) {
            throw new RgsException(ErrorCode.DUPLICATE_TRANSACTION,
                    "Original transaction already rolled back: " + req.originalTransactionId());
        }

        Money amount = Money.fromMinor(original.getAmountMinor(), original.getCurrency());
        return withRetry(() -> ledgerWriter.rollback(req, idempotencyKey, original, amount));
    }

    // ------------------------------------------------------------------ helpers

    private void validatePlayerCurrency(String requestCurrency, String jwtCurrency) {
        if (requestCurrency == null || !Money.isSupported(requestCurrency)) {
            throw new RgsException(ErrorCode.VALIDATION_ERROR,
                    "Unsupported currency: " + requestCurrency);
        }
        if (jwtCurrency != null && !jwtCurrency.equals(requestCurrency)) {
            throw new RgsException(ErrorCode.CURRENCY_MISMATCH,
                    "JWT currency '" + jwtCurrency + "' does not match request currency '"
                            + requestCurrency + "'");
        }
    }

    private Money toMoney(BigDecimal amount, String currency) {
        try {
            return Money.of(amount, currency);
        } catch (IllegalArgumentException ex) {
            throw new RgsException(ErrorCode.VALIDATION_ERROR, ex.getMessage(), ex);
        }
    }

    private <T> T withRetry(Supplier<T> action) {
        OptimisticLockingFailureException last = null;
        for (int attempt = 0; attempt < MAX_OPTIMISTIC_RETRIES; attempt++) {
            try {
                return action.get();
            } catch (OptimisticLockingFailureException ex) {
                last = ex;
                log.debug("Wallet optimistic-lock conflict, attempt {}/{}",
                        attempt + 1, MAX_OPTIMISTIC_RETRIES);
            }
        }
        throw new RgsException(ErrorCode.SESSION_VERSION_CONFLICT,
                "Wallet balance contention exceeded retry budget", last);
    }
}
