package com.velocity.rgs.wallet.api;

import com.velocity.rgs.common.error.ErrorCode;
import com.velocity.rgs.common.error.RgsException;
import com.velocity.rgs.common.idempotency.Idempotent;
import com.velocity.rgs.config.PlayerContext;
import com.velocity.rgs.wallet.service.InternalWalletService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.velocity.rgs.common.idempotency.IdempotencyAspect.HEADER_KEY;

/**
 * Wallet REST surface. In {@code demo} / {@code wallet-internal} this is the live
 * wallet API; in {@code wallet-operator} the real wallet is external and this
 * controller is not exposed (gateway is the only call path).
 */
@RestController
@RequestMapping("/api/v1/wallet")
@Profile({"default", "demo", "wallet-internal", "test", "simulator"})
@RequiredArgsConstructor
public class WalletController {

    private final InternalWalletService walletService;
    private final PlayerContext playerContext;

    @PostMapping("/authenticate")
    public ResponseEntity<WalletAuthenticateResponse> authenticate(
            @Valid @RequestBody WalletAuthenticateRequest request) {
        assertPlayerMatchesContext(request.playerId());
        return ResponseEntity.ok(walletService.authenticate(request, playerContext.getCurrency()));
    }

    @GetMapping("/balance")
    public ResponseEntity<WalletBalanceResponse> balance() {
        String playerId = requirePlayerId();
        return ResponseEntity.ok(walletService.balance(playerId));
    }

    @PostMapping("/debit")
    @Idempotent(scope = "wallet:debit:{playerId}:{transactionId}", ttlHours = 48)
    public ResponseEntity<WalletDebitResponse> debit(
            @RequestHeader(value = HEADER_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody WalletDebitRequest request) {
        assertPlayerMatchesContext(request.playerId());
        return ResponseEntity.ok(walletService.debit(request, idempotencyKey, playerContext.getCurrency()));
    }

    @PostMapping("/credit")
    @Idempotent(scope = "wallet:credit:{playerId}:{transactionId}", ttlHours = 48)
    public ResponseEntity<WalletCreditResponse> credit(
            @RequestHeader(value = HEADER_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody WalletCreditRequest request) {
        assertPlayerMatchesContext(request.playerId());
        return ResponseEntity.ok(walletService.credit(request, idempotencyKey, playerContext.getCurrency()));
    }

    @PostMapping("/rollback")
    @Idempotent(scope = "wallet:rollback:{playerId}:{originalTransactionId}", ttlHours = 48)
    public ResponseEntity<WalletRollbackResponse> rollback(
            @RequestHeader(value = HEADER_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody WalletRollbackRequest request) {
        assertPlayerMatchesContext(request.playerId());
        return ResponseEntity.ok(walletService.rollback(request, idempotencyKey, playerContext.getCurrency()));
    }

    private String requirePlayerId() {
        String playerId = playerContext.getPlayerId();
        if (playerId == null || playerId.isBlank()) {
            throw new RgsException(ErrorCode.AUTH_FAILED, "No authenticated player");
        }
        return playerId;
    }

    private void assertPlayerMatchesContext(String requestPlayerId) {
        String authenticated = requirePlayerId();
        if (!authenticated.equals(requestPlayerId)) {
            throw new RgsException(ErrorCode.FORBIDDEN_ACTION,
                    "Request player '" + requestPlayerId + "' does not match authenticated principal");
        }
    }
}
