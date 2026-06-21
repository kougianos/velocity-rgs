package com.velocity.rgs.wallet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocity.rgs.common.idempotency.IdempotencyAspect;
import com.velocity.rgs.common.idempotency.IdempotencyRecordRepository;
import com.velocity.rgs.testsupport.JwtTestFactory;
import com.velocity.rgs.testsupport.RgsIntegrationTest;
import com.velocity.rgs.wallet.api.WalletAuthenticateRequest;
import com.velocity.rgs.wallet.api.WalletAuthenticateResponse;
import com.velocity.rgs.wallet.api.WalletBalanceResponse;
import com.velocity.rgs.wallet.api.WalletCreditRequest;
import com.velocity.rgs.wallet.api.WalletDebitRequest;
import com.velocity.rgs.wallet.api.WalletDebitResponse;
import com.velocity.rgs.wallet.api.WalletRollbackRequest;
import com.velocity.rgs.wallet.api.WalletRollbackResponse;
import com.velocity.rgs.wallet.domain.RollbackReason;
import com.velocity.rgs.wallet.domain.WalletTransaction;
import com.velocity.rgs.wallet.domain.WalletTransactionStatus;
import com.velocity.rgs.wallet.domain.WalletTransactionType;
import com.velocity.rgs.wallet.persistence.WalletBalanceRepository;
import com.velocity.rgs.wallet.persistence.WalletTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@RgsIntegrationTest
@AutoConfigureMockMvc
class WalletControllerIntegrationTest {

    private static final String PLAYER = "p-wallet-1";
    private static final String SESSION = "s-2001";
    private static final String ROUND = "r-3001";
    private static final String CURRENCY = "EUR";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private WalletBalanceRepository balanceRepository;
    @Autowired private WalletTransactionRepository transactionRepository;
    @Autowired private IdempotencyRecordRepository idempotencyRepository;

    @BeforeEach
    void clean() {
        transactionRepository.deleteAll();
        idempotencyRepository.deleteAll();
        balanceRepository.deleteAll();
    }

    @Test
    void authenticateSeedsDemoPlayerOnFirstCall() throws Exception {
        WalletAuthenticateResponse res = perform(post("/api/v1/wallet/authenticate"),
                new WalletAuthenticateRequest(PLAYER), null,
                WalletAuthenticateResponse.class);

        assertThat(res.playerId()).isEqualTo(PLAYER);
        assertThat(res.currency()).isEqualTo(CURRENCY);
        assertThat(res.eligible()).isTrue();
        assertThat(res.balance()).isEqualByComparingTo(new BigDecimal("10000.00"));
        assertThat(balanceRepository.findById(PLAYER)).isPresent();
    }

    @Test
    void balanceReturnsCurrentAmount() throws Exception {
        seedPlayer();

        WalletBalanceResponse res = mockMvc.perform(get("/api/v1/wallet/balance")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt()))
                .andReturn().getResponse().getContentAsString()
                .transform(json -> readJson(json, WalletBalanceResponse.class));

        assertThat(res.playerId()).isEqualTo(PLAYER);
        assertThat(res.currency()).isEqualTo(CURRENCY);
        assertThat(res.balance()).isEqualByComparingTo(new BigDecimal("10000.00"));
    }

    @Test
    void debitDeductsBalanceAndPersistsLedger() throws Exception {
        seedPlayer();

        String txId = "t-debit-1";
        WalletDebitResponse res = doDebit(txId, "idem-debit-1", new BigDecimal("1.50"));

        assertThat(res.status()).isEqualTo(WalletTransactionStatus.SUCCESS);
        assertThat(res.balanceBefore()).isEqualByComparingTo(new BigDecimal("10000.00"));
        assertThat(res.balanceAfter()).isEqualByComparingTo(new BigDecimal("9998.50"));

        WalletTransaction ledger = transactionRepository.findByTransactionId(txId).orElseThrow();
        assertThat(ledger.getType()).isEqualTo(WalletTransactionType.BET);
        assertThat(ledger.getAmount()).isEqualByComparingTo(new BigDecimal("1.50"));
        assertThat(ledger.getIdempotencyKey()).isEqualTo("idem-debit-1");

        assertThat(balanceRepository.findById(PLAYER).orElseThrow().getBalanceMinor())
                .isEqualTo(999_850L);
    }

    @Test
    void debitIdempotentReplayReturnsCachedResponseAndDoesNotDoubleDebit() throws Exception {
        seedPlayer();

        String txId = "t-debit-2";
        WalletDebitResponse first = doDebit(txId, "idem-debit-2", new BigDecimal("2.00"));
        MvcResult replayResult = postRaw("/api/v1/wallet/debit",
                debitRequest(txId, new BigDecimal("2.00")), "idem-debit-2");

        assertThat(replayResult.getResponse().getStatus()).isEqualTo(200);
        assertThat(replayResult.getResponse().getHeader(IdempotencyAspect.HEADER_REPLAY)).isEqualTo("true");
        WalletDebitResponse replay = readJson(replayResult.getResponse().getContentAsString(),
                WalletDebitResponse.class);
        assertThat(replay).usingRecursiveComparison().isEqualTo(first);

        assertThat(transactionRepository.findAll()).hasSize(1);
        assertThat(balanceRepository.findById(PLAYER).orElseThrow().getBalanceMinor())
                .isEqualTo(999_800L);
    }

    @Test
    void debitSameTxIdDifferentKeyRaisesDuplicateTransaction() throws Exception {
        seedPlayer();
        String txId = "t-debit-3";
        doDebit(txId, "idem-A", new BigDecimal("3.00"));

        MvcResult res = postRaw("/api/v1/wallet/debit",
                debitRequest(txId, new BigDecimal("3.00")), "idem-B");
        assertThat(res.getResponse().getStatus()).isEqualTo(409);
        assertThat(res.getResponse().getContentAsString()).contains("DUPLICATE_TRANSACTION");
    }

    @Test
    void debitInsufficientFundsReturnsConflict() throws Exception {
        seedPlayer();

        MvcResult res = postRaw("/api/v1/wallet/debit",
                debitRequest("t-debit-poor", new BigDecimal("100000.00")), "idem-poor");
        assertThat(res.getResponse().getStatus()).isEqualTo(409);
        assertThat(res.getResponse().getContentAsString()).contains("INSUFFICIENT_FUNDS");
        assertThat(transactionRepository.findAll()).isEmpty();
        assertThat(balanceRepository.findById(PLAYER).orElseThrow().getBalanceMinor())
                .isEqualTo(1_000_000L);
    }

    @Test
    void debitCurrencyMismatchAgainstJwtIsRejected() throws Exception {
        seedPlayer();

        WalletDebitRequest body = new WalletDebitRequest(PLAYER, SESSION, ROUND,
                "t-cur-mismatch", new BigDecimal("1.00"), "USD", WalletTransactionType.BET);

        MvcResult res = postRaw("/api/v1/wallet/debit", body, "idem-cur");
        assertThat(res.getResponse().getStatus()).isEqualTo(409);
        assertThat(res.getResponse().getContentAsString()).contains("CURRENCY_MISMATCH");
    }

    @Test
    void creditAndRollbackFullFlow() throws Exception {
        seedPlayer();

        doDebit("t-bet-1", "idem-bet-1", new BigDecimal("5.00"));

        WalletCreditRequest creditBody = new WalletCreditRequest(PLAYER, SESSION, ROUND,
                "t-win-1", new BigDecimal("12.50"), CURRENCY, WalletTransactionType.WIN);
        MvcResult creditRes = postRaw("/api/v1/wallet/credit", creditBody, "idem-win-1");
        assertThat(creditRes.getResponse().getStatus()).isEqualTo(200);
        assertThat(balanceRepository.findById(PLAYER).orElseThrow().getBalanceMinor())
                .isEqualTo(10_000_00L - 5_00L + 12_50L);

        WalletRollbackRequest rollbackBody = new WalletRollbackRequest(
                PLAYER, "t-bet-1", "t-rb-1", RollbackReason.DOWNSTREAM_FAILURE);
        MvcResult rbRes = postRaw("/api/v1/wallet/rollback", rollbackBody, "idem-rb-1");
        assertThat(rbRes.getResponse().getStatus()).isEqualTo(200);
        WalletRollbackResponse rb = readJson(rbRes.getResponse().getContentAsString(),
                WalletRollbackResponse.class);
        assertThat(rb.status()).isEqualTo(WalletTransactionStatus.SUCCESS);
        assertThat(rb.originalTransactionId()).isEqualTo("t-bet-1");

        assertThat(balanceRepository.findById(PLAYER).orElseThrow().getBalanceMinor())
                .isEqualTo(10_000_00L + 12_50L);
    }

    @Test
    void rollbackUnknownOriginalReturns404() throws Exception {
        seedPlayer();

        WalletRollbackRequest body = new WalletRollbackRequest(
                PLAYER, "t-nope", "t-rb-x", RollbackReason.TECHNICAL_ERROR);

        MvcResult res = postRaw("/api/v1/wallet/rollback", body, "idem-rb-x");
        assertThat(res.getResponse().getStatus()).isEqualTo(404);
        assertThat(res.getResponse().getContentAsString()).contains("ORIGINAL_TRANSACTION_NOT_FOUND");
    }

    @Test
    void rollbackTwiceForSameOriginalRejected() throws Exception {
        seedPlayer();
        doDebit("t-bet-r", "idem-bet-r", new BigDecimal("4.00"));

        WalletRollbackRequest first = new WalletRollbackRequest(
                PLAYER, "t-bet-r", "t-rb-r1", RollbackReason.DOWNSTREAM_FAILURE);
        assertThat(postRaw("/api/v1/wallet/rollback", first, "idem-rb-r1")
                .getResponse().getStatus()).isEqualTo(200);

        WalletRollbackRequest second = new WalletRollbackRequest(
                PLAYER, "t-bet-r", "t-rb-r2", RollbackReason.OPERATOR_CANCEL);
        MvcResult res = postRaw("/api/v1/wallet/rollback", second, "idem-rb-r2");
        assertThat(res.getResponse().getStatus()).isEqualTo(409);
        assertThat(res.getResponse().getContentAsString()).contains("DUPLICATE_TRANSACTION");
    }

    @Test
    void concurrentDebitsResolveViaOptimisticLockRetry() throws Exception {
        seedPlayer();

        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var futures = List.of(
                    executor.submit(() -> postRaw("/api/v1/wallet/debit",
                            debitRequest("t-c-1", new BigDecimal("1.00")), "idem-c-1")),
                    executor.submit(() -> postRaw("/api/v1/wallet/debit",
                            debitRequest("t-c-2", new BigDecimal("2.00")), "idem-c-2"))
            );
            for (var f : futures) {
                assertThat(f.get().getResponse().getStatus()).isEqualTo(200);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(transactionRepository.findAll()).hasSize(2);
        assertThat(balanceRepository.findById(PLAYER).orElseThrow().getBalanceMinor())
                .isEqualTo(10_000_00L - 1_00L - 2_00L);
    }

    @Test
    void unauthorizedRequestIsRejected() throws Exception {
        MvcResult res = mockMvc.perform(get("/api/v1/wallet/balance")).andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void playerIdMismatchIsForbidden() throws Exception {
        seedPlayer();
        WalletDebitRequest body = new WalletDebitRequest("p-other", SESSION, ROUND,
                "t-forbidden-1", new BigDecimal("1.00"), CURRENCY, WalletTransactionType.BET);
        MvcResult res = postRaw("/api/v1/wallet/debit", body, "idem-forb-1");
        assertThat(res.getResponse().getStatus()).isEqualTo(403);
        assertThat(res.getResponse().getContentAsString()).contains("FORBIDDEN_ACTION");
    }

    // ------------------------------------------------------------------ helpers

    private void seedPlayer() throws Exception {
        perform(post("/api/v1/wallet/authenticate"),
                new WalletAuthenticateRequest(PLAYER), null,
                WalletAuthenticateResponse.class);
    }

    private WalletDebitResponse doDebit(String txId, String idemKey, BigDecimal amount) throws Exception {
        MvcResult res = postRaw("/api/v1/wallet/debit", debitRequest(txId, amount), idemKey);
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        return readJson(res.getResponse().getContentAsString(), WalletDebitResponse.class);
    }

    private WalletDebitRequest debitRequest(String txId, BigDecimal amount) {
        return new WalletDebitRequest(PLAYER, SESSION, ROUND, txId, amount, CURRENCY,
                WalletTransactionType.BET);
    }

    private MvcResult postRaw(String url, Object body, String idemKey) throws Exception {
        var req = post(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body));
        if (idemKey != null) {
            req.header(IdempotencyAspect.HEADER_KEY, idemKey);
        }
        return mockMvc.perform(req).andReturn();
    }

    private <T> T perform(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
                          Object body, String idemKey, Class<T> responseType) throws Exception {
        builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body));
        if (idemKey != null) {
            builder.header(IdempotencyAspect.HEADER_KEY, idemKey);
        }
        MvcResult res = mockMvc.perform(builder).andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        return readJson(res.getResponse().getContentAsString(), responseType);
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private String jwt() {
        return JwtTestFactory.validToken(PLAYER, SESSION, CURRENCY);
    }

    @SuppressWarnings("unused")
    private String randomTxId() {
        return "t-" + UUID.randomUUID();
    }
}
