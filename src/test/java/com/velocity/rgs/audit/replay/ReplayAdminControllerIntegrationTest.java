package com.velocity.rgs.audit.replay;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@RgsIntegrationTest
@AutoConfigureMockMvc
class ReplayAdminControllerIntegrationTest {

    private static final String PLAYER = "p-replay-1";
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
    void replayReconstructsRoundExactly() throws Exception {
        JsonNode init = postJson("/api/v1/slot/init", null, playerJwt(),
                mapper.createObjectNode().put("gameId", GAME_ID).put("currency", CURRENCY).toString());
        String sessionId = init.get("sessionId").asText();
        long version = init.get("sessionVersion").asLong();

        // A single base-game spin is enough - we just need a persisted round to replay
        String body = mapper.createObjectNode()
                .put("gameId", GAME_ID)
                .put("sessionId", sessionId)
                .put("sessionVersion", version)
                .put("betSize", "1.00")
                .put("powerBetActive", false)
                .toString();
        JsonNode spin = postJson("/api/v1/slot/spin", "idem-replay-spin", playerJwt(), body);
        String roundId = spin.get("roundId").asText();

        // Admin replay succeeds and reports a matching matrix
        MvcResult res = mockMvc.perform(post("/api/v1/admin/replay/" + roundId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + JwtTestFactory.adminToken("p-admin"))
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn();

        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        JsonNode result = mapper.readTree(res.getResponse().getContentAsString());
        assertThat(result.get("matrixMatches").asBoolean()).isTrue();
        assertThat(result.get("totalWinMatches").asBoolean()).isTrue();
        assertThat(result.get("roundId").asText()).isEqualTo(roundId);
    }

    @Test
    void replayRequiresAdminRole() throws Exception {
        JsonNode init = postJson("/api/v1/slot/init", null, playerJwt(),
                mapper.createObjectNode().put("gameId", GAME_ID).put("currency", CURRENCY).toString());
        String sessionId = init.get("sessionId").asText();
        long version = init.get("sessionVersion").asLong();
        String body = mapper.createObjectNode()
                .put("gameId", GAME_ID).put("sessionId", sessionId).put("sessionVersion", version)
                .put("betSize", "1.00").put("powerBetActive", false).toString();
        JsonNode spin = postJson("/api/v1/slot/spin", "idem-replay-forbidden", playerJwt(), body);

        // Non-admin JWT → 403
        MvcResult res = mockMvc.perform(post("/api/v1/admin/replay/" + spin.get("roundId").asText())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + playerJwt())
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(403);
        assertThat(res.getResponse().getContentAsString()).contains("FORBIDDEN_ACTION");
    }

    @Test
    void replayReturns404ForUnknownRound() throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/admin/replay/rnd-does-not-exist")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + JwtTestFactory.adminToken("p-admin"))
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(404);
        assertThat(res.getResponse().getContentAsString()).contains("SESSION_NOT_FOUND");
    }

    // helpers
    private JsonNode postJson(String url, String idemKey, String jwt, String body) throws Exception {
        var req = post(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
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
