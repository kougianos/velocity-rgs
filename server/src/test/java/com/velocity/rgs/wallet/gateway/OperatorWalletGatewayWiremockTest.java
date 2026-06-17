package com.velocity.rgs.wallet.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.velocity.rgs.common.error.ErrorCode;
import com.velocity.rgs.common.error.RgsException;
import com.velocity.rgs.wallet.api.WalletAuthenticateRequest;
import com.velocity.rgs.wallet.api.WalletAuthenticateResponse;
import com.velocity.rgs.wallet.api.WalletBalanceResponse;
import com.velocity.rgs.wallet.api.WalletDebitRequest;
import com.velocity.rgs.wallet.api.WalletDebitResponse;
import com.velocity.rgs.wallet.api.WalletRollbackRequest;
import com.velocity.rgs.wallet.domain.RollbackReason;
import com.velocity.rgs.wallet.domain.WalletTransactionStatus;
import com.velocity.rgs.wallet.domain.WalletTransactionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Instant;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit-style WireMock contract test for the operator wallet gateway. Spring is intentionally not
 * booted: we wire the production {@link WebClient} configuration manually so we exercise the same
 * filter chain that runs in {@code wallet-operator} mode without dragging the JPA stack along.
 */
class OperatorWalletGatewayWiremockTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private WireMockServer wireMock;
    private OperatorWalletGateway gateway;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();

        OperatorWalletProperties props = new OperatorWalletProperties();
        props.setBaseUrl(wireMock.baseUrl());
        props.setTimeoutMs(2000);
        WebClient client = WebClient.builder()
                .baseUrl(wireMock.baseUrl())
                .filter(OperatorWalletConfiguration.errorTranslatingFilter(mapper))
                .build();
        gateway = new OperatorWalletGateway(client, props);
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Test
    void authenticateParsesResponse() {
        WalletAuthenticateResponse expected =
                new WalletAuthenticateResponse("p-1", "EUR", new BigDecimal("100.00"), true);
        wireMock.stubFor(post(urlEqualTo("/api/v1/wallet/authenticate"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(writeJson(expected))));

        WalletAuthenticateResponse actual =
                gateway.authenticate(new WalletAuthenticateRequest("p-1"), "EUR");

        assertThat(actual.playerId()).isEqualTo("p-1");
        assertThat(actual.balance()).isEqualByComparingTo("100.00");
        wireMock.verify(postRequestedFor(urlEqualTo("/api/v1/wallet/authenticate")));
    }

    @Test
    void balanceQueriesByPlayerId() {
        wireMock.stubFor(get(urlEqualTo("/api/v1/wallet/balance?playerId=p-2"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(writeJson(new WalletBalanceResponse("p-2", new BigDecimal("42.00"), "EUR")))));

        WalletBalanceResponse res = gateway.balance("p-2");

        assertThat(res.balance()).isEqualByComparingTo("42.00");
        wireMock.verify(getRequestedFor(urlEqualTo("/api/v1/wallet/balance?playerId=p-2")));
    }

    @Test
    void debitForwardsIdempotencyKeyHeader() {
        WalletDebitResponse expected = new WalletDebitResponse("t-1", WalletTransactionStatus.SUCCESS,
                new BigDecimal("100.00"), new BigDecimal("98.50"), "EUR", Instant.parse("2024-01-01T00:00:00Z"), false);
        wireMock.stubFor(post(urlEqualTo("/api/v1/wallet/debit"))
                .withHeader(OperatorWalletGateway.IDEMPOTENCY_HEADER, equalTo("idem-debit-1"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(writeJson(expected))));

        WalletDebitResponse actual = gateway.debit(
                new WalletDebitRequest("p-3", "s-3", "r-3", "t-1",
                        new BigDecimal("1.50"), "EUR", WalletTransactionType.BET),
                "idem-debit-1", "EUR");

        assertThat(actual.balanceAfter()).isEqualByComparingTo("98.50");
        wireMock.verify(postRequestedFor(urlEqualTo("/api/v1/wallet/debit"))
                .withHeader(OperatorWalletGateway.IDEMPOTENCY_HEADER, equalTo("idem-debit-1")));
    }

    @Test
    void mapsNotFoundResponseToOriginalTransactionNotFound() {
        wireMock.stubFor(post(urlEqualTo("/api/v1/wallet/rollback"))
                .willReturn(aResponse().withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":\"ORIGINAL_TRANSACTION_NOT_FOUND\",\"message\":\"nope\",\"httpStatus\":404}")));

        assertThatThrownBy(() -> gateway.rollback(
                new WalletRollbackRequest("p-4", "t-missing", "rb-1", RollbackReason.DOWNSTREAM_FAILURE),
                "idem-rb-1", "EUR"))
                .isInstanceOfSatisfying(RgsException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ORIGINAL_TRANSACTION_NOT_FOUND));
    }

    @Test
    void mapsConflictResponseToDuplicateTransaction() {
        wireMock.stubFor(post(urlEqualTo("/api/v1/wallet/debit"))
                .willReturn(aResponse().withStatus(409)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":\"DUPLICATE_TRANSACTION\",\"message\":\"already debited\",\"httpStatus\":409}")));

        assertThatThrownBy(() -> gateway.debit(
                new WalletDebitRequest("p-5", "s-5", "r-5", "t-dup",
                        new BigDecimal("1.00"), "EUR", WalletTransactionType.BET),
                "idem-dup-1", "EUR"))
                .isInstanceOfSatisfying(RgsException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_TRANSACTION));
    }

    @Test
    void fallsBackToInternalErrorOn500WithoutBody() {
        wireMock.stubFor(post(urlEqualTo("/api/v1/wallet/debit"))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> gateway.debit(
                new WalletDebitRequest("p-6", "s-6", "r-6", "t-x",
                        new BigDecimal("1.00"), "EUR", WalletTransactionType.BET),
                "idem-err", "EUR"))
                .isInstanceOfSatisfying(RgsException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR));
    }

    private String writeJson(Object obj) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
