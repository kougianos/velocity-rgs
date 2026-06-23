package com.velocity.rgs.qa.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocity.rgs.common.idempotency.IdempotencyAspect;
import com.velocity.rgs.common.idempotency.IdempotencyRecordRepository;
import com.velocity.rgs.slot.persistence.FeaturePurchaseEventRepository;
import com.velocity.rgs.slot.persistence.GameRoundRepository;
import com.velocity.rgs.slot.persistence.PickCollectSnapshotRepository;
import com.velocity.rgs.session.persistence.GameSessionRepository;
import com.velocity.rgs.session.service.SessionStore;
import com.velocity.rgs.testsupport.JwtTestFactory;
import com.velocity.rgs.testsupport.RgsIntegrationTest;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@RgsIntegrationTest
@AutoConfigureMockMvc
class AdminQaControllerIntegrationTest {

    private static final String PLAYER = "p-qa-admin-1";
    private static final String CURRENCY = "EUR";
    private static final String GAME_ID = "aztec-fire";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private GameSessionRepository sessionRepository;
    @Autowired private SessionStore sessionStore;
    @Autowired private GameRoundRepository roundRepository;
    @Autowired private FeaturePurchaseEventRepository featurePurchaseRepository;
    @Autowired private PickCollectSnapshotRepository pickRepository;
    @Autowired private WalletBalanceRepository walletBalanceRepository;
    @Autowired private WalletTransactionRepository walletTransactionRepository;
    @Autowired private IdempotencyRecordRepository idempotencyRepository;

    @BeforeEach
    void clean() {
        roundRepository.deleteAll();
        featurePurchaseRepository.deleteAll();
        pickRepository.deleteAll();
        idempotencyRepository.deleteAll();
        sessionRepository.deleteAll();
        walletTransactionRepository.deleteAll();
        walletBalanceRepository.deleteAll();
        sessionStore.evict(PLAYER);
    }

    @Test
    void setBalanceCreatesRowWhenAbsent() throws Exception {
        String body = mapper.createObjectNode()
                .put("playerId", PLAYER).put("currency", CURRENCY)
                .put("balance", "250.00").toString();

        MvcResult res = mockMvc.perform(post("/api/v1/admin/wallet/balance")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + JwtTestFactory.adminToken("p-admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();

        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        JsonNode out = mapper.readTree(res.getResponse().getContentAsString());
        assertThat(out.get("playerId").asText()).isEqualTo(PLAYER);
        assertThat(out.get("currency").asText()).isEqualTo(CURRENCY);
        assertThat(out.get("balance").decimalValue()).isEqualByComparingTo("250.00");
        assertThat(walletBalanceRepository.findById(PLAYER)).isPresent()
                .hasValueSatisfying(b -> assertThat(b.getBalanceMinor()).isEqualTo(25_000L));
    }

    @Test
    void setBalanceUpdatesExistingRow() throws Exception {
        // seed via auth flow
        seedSessionAndSpin();

        String body = mapper.createObjectNode()
                .put("playerId", PLAYER).put("currency", CURRENCY)
                .put("balance", "1.23").toString();
        mockMvc.perform(post("/api/v1/admin/wallet/balance")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + JwtTestFactory.adminToken("p-admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(200));
        assertThat(walletBalanceRepository.findById(PLAYER)).hasValueSatisfying(b ->
                assertThat(b.getBalanceMinor()).isEqualTo(123L));
    }

    @Test
    void setBalanceRequiresAdmin() throws Exception {
        String body = mapper.createObjectNode()
                .put("playerId", PLAYER).put("currency", CURRENCY)
                .put("balance", "10.00").toString();

        MvcResult res = mockMvc.perform(post("/api/v1/admin/wallet/balance")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + playerJwt())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(403);
        assertThat(res.getResponse().getContentAsString()).contains("FORBIDDEN_ACTION");
    }

    @Test
    void inspectSessionReturnsPersistedStateAndCacheFlag() throws Exception {
        String sessionId = seedSessionAndSpin();
        MvcResult res = mockMvc.perform(get("/api/v1/admin/session/" + PLAYER)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + JwtTestFactory.adminToken("p-admin")))
                .andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = mapper.readTree(res.getResponse().getContentAsString());
        assertThat(body.get("sessionId").asText()).isEqualTo(sessionId);
        assertThat(body.get("playerId").asText()).isEqualTo(PLAYER);
        assertThat(body.get("gameId").asText()).isEqualTo(GAME_ID);
        assertThat(body.has("cachedInRedis")).isTrue();
    }

    @Test
    void inspectSessionReturns404ForUnknown() throws Exception {
        MvcResult res = mockMvc.perform(get("/api/v1/admin/session/p-does-not-exist")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + JwtTestFactory.adminToken("p-admin")))
                .andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(404);
        assertThat(res.getResponse().getContentAsString()).contains("SESSION_NOT_FOUND");
    }

    @Test
    void inspectRoundReturnsFullPersistedRound() throws Exception {
        String roundId = seedRound();
        MvcResult res = mockMvc.perform(get("/api/v1/admin/round/" + roundId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + JwtTestFactory.adminToken("p-admin")))
                .andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = mapper.readTree(res.getResponse().getContentAsString());
        assertThat(body.get("roundId").asText()).isEqualTo(roundId);
        assertThat(body.get("playerId").asText()).isEqualTo(PLAYER);
        assertThat(body.get("matrix").isArray()).isTrue();
        assertThat(body.get("rngDraws").isArray()).isTrue();
    }

    @Test
    void inspectRoundRequiresAdmin() throws Exception {
        String roundId = seedRound();
        MvcResult res = mockMvc.perform(get("/api/v1/admin/round/" + roundId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + playerJwt()))
                .andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(403);
    }

    // helpers ---------------------------------------------------------------------

    private String seedSessionAndSpin() throws Exception {
        JsonNode init = postJson("/api/v1/slot/init", null, playerJwt(),
                mapper.createObjectNode().put("gameId", GAME_ID).put("currency", CURRENCY).toString());
        return init.get("sessionId").asText();
    }

    private String seedRound() throws Exception {
        JsonNode init = postJson("/api/v1/slot/init", null, playerJwt(),
                mapper.createObjectNode().put("gameId", GAME_ID).put("currency", CURRENCY).toString());
        String sessionId = init.get("sessionId").asText();
        long version = init.get("sessionVersion").asLong();
        String body = mapper.createObjectNode()
                .put("gameId", GAME_ID).put("sessionId", sessionId).put("sessionVersion", version)
                .put("betSize", "1.00").put("powerBetActive", false).toString();
        JsonNode spin = postJson("/api/v1/slot/spin", "idem-admin-seed-" + UUID.randomUUID(), playerJwt(), body);
        return spin.get("roundId").asText();
    }

    private JsonNode postJson(String url, String idemKey, String jwt, String body) throws Exception {
        var req = post(url).header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (idemKey != null) {
            req.header(IdempotencyAspect.HEADER_KEY, idemKey);
        }
        MvcResult res = mockMvc.perform(req).andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        return mapper.readTree(res.getResponse().getContentAsString());
    }

    private String playerJwt() {
        return JwtTestFactory.validToken(PLAYER, "ses-" + UUID.randomUUID(), CURRENCY);
    }
}
