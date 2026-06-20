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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import java.time.Duration;
import java.util.Objects;

/**
 * Operator wallet integration (M6 Task 6.4). Active when {@code rgs.wallet.mode=operator}; the engine
 * remains unchanged because everything is wired through {@link WalletGateway}.
 *
 * <p>All mutating calls forward the {@code Idempotency-Key} header to the upstream operator wallet so
 * its server-side replay store can de-dupe retries identically to our in-process flow. Calls block on
 * the reactive pipeline because the rest of the engine is synchronous; a configured per-call timeout
 * prevents indefinite blocking.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "rgs.wallet", name = "mode", havingValue = "operator")
@RequiredArgsConstructor
public class OperatorWalletGateway implements WalletGateway {

    static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final WebClient operatorWalletWebClient;
    private final OperatorWalletProperties properties;

    @Override
    public WalletAuthenticateResponse authenticate(WalletAuthenticateRequest request, String jwtCurrency) {
        return post("/api/v1/wallet/authenticate", request, null, WalletAuthenticateResponse.class);
    }

    @Override
    public WalletBalanceResponse balance(String playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return get("/api/v1/wallet/balance?playerId=" + playerId, WalletBalanceResponse.class);
    }

    @Override
    public WalletDebitResponse debit(WalletDebitRequest request, String idempotencyKey, String jwtCurrency) {
        return post("/api/v1/wallet/debit", request, idempotencyKey, WalletDebitResponse.class);
    }

    @Override
    public WalletCreditResponse credit(WalletCreditRequest request, String idempotencyKey, String jwtCurrency) {
        return post("/api/v1/wallet/credit", request, idempotencyKey, WalletCreditResponse.class);
    }

    @Override
    public WalletRollbackResponse rollback(WalletRollbackRequest request, String idempotencyKey, String jwtCurrency) {
        return post("/api/v1/wallet/rollback", request, idempotencyKey, WalletRollbackResponse.class);
    }

    private <T> T post(String path, Object body, String idempotencyKey, Class<T> responseType) {
        try {
            WebClient.RequestBodySpec spec = operatorWalletWebClient.post().uri(path);
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                spec = (WebClient.RequestBodySpec) spec.header(IDEMPOTENCY_HEADER, idempotencyKey);
            }
            return spec.bodyValue(body)
                    .retrieve()
                    .bodyToMono(responseType)
                    .block(Duration.ofMillis(properties.getTimeoutMs()));
        } catch (RgsException ex) {
            throw ex;
        } catch (WebClientRequestException ex) {
            throw timeout(path, ex);
        } catch (RuntimeException ex) {
            if (ex.getCause() instanceof RgsException rgs) {
                throw rgs;
            }
            throw new RgsException(ErrorCode.INTERNAL_ERROR,
                    "Operator wallet call failed: " + path + " (" + ex.getMessage() + ")", ex);
        }
    }

    private <T> T get(String path, Class<T> responseType) {
        try {
            return operatorWalletWebClient.get().uri(path)
                    .retrieve()
                    .bodyToMono(responseType)
                    .block(Duration.ofMillis(properties.getTimeoutMs()));
        } catch (RgsException ex) {
            throw ex;
        } catch (WebClientRequestException ex) {
            throw timeout(path, ex);
        } catch (RuntimeException ex) {
            if (ex.getCause() instanceof RgsException rgs) {
                throw rgs;
            }
            throw new RgsException(ErrorCode.INTERNAL_ERROR,
                    "Operator wallet call failed: " + path + " (" + ex.getMessage() + ")", ex);
        }
    }

    private RgsException timeout(String path, RuntimeException cause) {
        log.error("Operator wallet network failure on {}: {}", path, cause.getMessage());
        return new RgsException(ErrorCode.INTERNAL_ERROR,
                "Operator wallet network failure: " + path, cause);
    }
}
