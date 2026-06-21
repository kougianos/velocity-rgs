package com.velocity.rgs.wallet.service;

import com.velocity.rgs.common.error.ErrorCode;
import com.velocity.rgs.common.error.RgsException;
import com.velocity.rgs.common.money.Money;
import com.velocity.rgs.wallet.api.WalletCreditRequest;
import com.velocity.rgs.wallet.api.WalletCreditResponse;
import com.velocity.rgs.wallet.api.WalletDebitRequest;
import com.velocity.rgs.wallet.api.WalletDebitResponse;
import com.velocity.rgs.wallet.api.WalletRollbackRequest;
import com.velocity.rgs.wallet.api.WalletRollbackResponse;
import com.velocity.rgs.wallet.domain.RollbackReason;
import com.velocity.rgs.wallet.domain.WalletBalance;
import com.velocity.rgs.wallet.domain.WalletTransaction;
import com.velocity.rgs.wallet.domain.WalletTransactionStatus;
import com.velocity.rgs.wallet.domain.WalletTransactionType;
import com.velocity.rgs.wallet.persistence.WalletBalanceRepository;
import com.velocity.rgs.wallet.persistence.WalletTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Transactional unit for wallet mutations. Lives in its own bean so the outer
 * {@link InternalWalletService} retry loop can re-enter a fresh transaction on
 * optimistic-lock conflicts (Spring AOP proxies do not intercept self-invocation).
 */
@Component
@RequiredArgsConstructor
public class WalletLedgerWriter {

    private final WalletBalanceRepository balanceRepository;
    private final WalletTransactionRepository transactionRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WalletDebitResponse debit(WalletDebitRequest req, String idempotencyKey, Money amount) {
        WalletBalance row = loadBalance(req.playerId(), req.currency());
        Money before = Money.fromMinor(row.getBalanceMinor(), row.getCurrency());
        if (before.subtract(amount).isNegative()) {
            throw new RgsException(ErrorCode.INSUFFICIENT_FUNDS,
                    "Player " + req.playerId() + " has insufficient funds for debit " + amount.amount());
        }
        Money after = before.subtract(amount);
        applyBalance(row, after);

        WalletTransaction tx = persistTransaction(WalletTransaction.builder()
                .playerId(req.playerId())
                .transactionId(req.transactionId())
                .sessionId(req.sessionId())
                .roundId(req.roundId())
                .type(req.transactionType() != null ? req.transactionType() : WalletTransactionType.BET)
                .status(WalletTransactionStatus.SUCCESS)
                .amount(amount.amount())
                .amountMinor(amount.toMinor())
                .currency(amount.currency())
                .balanceBefore(before.amount())
                .balanceAfter(after.amount())
                .idempotencyKey(idempotencyKey)
                .createdAt(Instant.now())
                .build());

        return new WalletDebitResponse(
                tx.getTransactionId(),
                tx.getStatus(),
                before.amount(),
                after.amount(),
                amount.currency(),
                tx.getCreatedAt(),
                false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WalletCreditResponse credit(WalletCreditRequest req, String idempotencyKey, Money amount) {
        WalletBalance row = loadBalance(req.playerId(), req.currency());
        Money before = Money.fromMinor(row.getBalanceMinor(), row.getCurrency());
        Money after = before.add(amount);
        applyBalance(row, after);

        WalletTransaction tx = persistTransaction(WalletTransaction.builder()
                .playerId(req.playerId())
                .transactionId(req.transactionId())
                .sessionId(req.sessionId())
                .roundId(req.roundId())
                .type(req.transactionType() != null ? req.transactionType() : WalletTransactionType.WIN)
                .status(WalletTransactionStatus.SUCCESS)
                .amount(amount.amount())
                .amountMinor(amount.toMinor())
                .currency(amount.currency())
                .balanceBefore(before.amount())
                .balanceAfter(after.amount())
                .idempotencyKey(idempotencyKey)
                .createdAt(Instant.now())
                .build());

        return new WalletCreditResponse(
                tx.getTransactionId(),
                tx.getStatus(),
                before.amount(),
                after.amount(),
                amount.currency(),
                tx.getCreatedAt(),
                false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WalletRollbackResponse rollback(WalletRollbackRequest req,
                                           String idempotencyKey,
                                           WalletTransaction original,
                                           Money amount) {
        WalletBalance row = loadBalance(original.getPlayerId(), original.getCurrency());
        Money before = Money.fromMinor(row.getBalanceMinor(), row.getCurrency());

        Money after = switch (original.getType()) {
            case BET, BONUS_BUY -> before.add(amount);
            case WIN, FEATURE_WIN -> {
                Money candidate = before.subtract(amount);
                if (candidate.isNegative()) {
                    throw new RgsException(ErrorCode.INSUFFICIENT_FUNDS,
                            "Cannot rollback credit — would drive balance negative");
                }
                yield candidate;
            }
            case ROLLBACK -> throw new RgsException(ErrorCode.VALIDATION_ERROR,
                    "Cannot rollback a rollback transaction");
        };
        applyBalance(row, after);

        RollbackReason reason = req.rollbackReason() != null
                ? req.rollbackReason()
                : RollbackReason.TECHNICAL_ERROR;

        WalletTransaction tx = persistTransaction(WalletTransaction.builder()
                .playerId(original.getPlayerId())
                .transactionId(req.transactionId())
                .originalTransactionId(original.getTransactionId())
                .sessionId(original.getSessionId())
                .roundId(original.getRoundId())
                .type(WalletTransactionType.ROLLBACK)
                .status(WalletTransactionStatus.SUCCESS)
                .amount(amount.amount())
                .amountMinor(amount.toMinor())
                .currency(amount.currency())
                .balanceBefore(before.amount())
                .balanceAfter(after.amount())
                .idempotencyKey(idempotencyKey)
                .rollbackReason(reason)
                .createdAt(Instant.now())
                .build());

        return new WalletRollbackResponse(
                tx.getTransactionId(),
                tx.getOriginalTransactionId(),
                tx.getStatus(),
                tx.getCreatedAt(),
                false);
    }

    private WalletBalance loadBalance(String playerId, String requestCurrency) {
        WalletBalance row = balanceRepository.findById(playerId)
                .orElseThrow(() -> new RgsException(ErrorCode.AUTH_FAILED,
                        "Unknown player: " + playerId));
        if (!row.getCurrency().equals(requestCurrency)) {
            throw new RgsException(ErrorCode.CURRENCY_MISMATCH,
                    "Wallet currency '" + row.getCurrency()
                            + "' does not match request currency '" + requestCurrency + "'");
        }
        return row;
    }

    private void applyBalance(WalletBalance row, Money next) {
        row.setBalance(next.amount().setScale(4, Money.ROUNDING));
        row.setBalanceMinor(next.toMinor());
        row.setUpdatedAt(Instant.now());
    }

    private WalletTransaction persistTransaction(WalletTransaction tx) {
        try {
            return transactionRepository.saveAndFlush(tx);
        } catch (DataIntegrityViolationException ex) {
            throw new RgsException(ErrorCode.DUPLICATE_TRANSACTION,
                    "Duplicate wallet transaction: " + tx.getTransactionId(), ex);
        }
    }
}
