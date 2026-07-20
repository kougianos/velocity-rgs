package com.velocity.rgs.audit.replay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocity.rgs.common.idempotency.IdempotencyAspect;
import com.velocity.rgs.common.idempotency.IdempotencyRecordRepository;
import com.velocity.rgs.config.SecurityProperties;
import com.velocity.rgs.session.persistence.GameSessionRepository;
import com.velocity.rgs.session.service.SessionStore;
import com.velocity.rgs.slot.persistence.FeaturePurchaseEventRepository;
import com.velocity.rgs.slot.persistence.GameRoundRepository;
import com.velocity.rgs.slot.persistence.PickCollectSnapshotRepository;
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

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * End-to-end cover for the public replay link (§3.1): mint one as an admin, then open it the way a
 * stranger would - no Authorization header at all.
 */
@RgsIntegrationTest
@AutoConfigureMockMvc
class PublicReplayIntegrationTest {

    private static final String PLAYER = "p-public-replay";
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
    @Autowired private SecurityProperties securityProperties;

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
    void aSharedLinkServesTheRoundToAnAnonymousCaller() throws Exception {
        String roundId = playOneRound("idem-public-1");
        JsonNode share = share(roundId);

        assertThat(share.get("verifiedMatrixMatches").asBoolean()).isTrue();
        assertThat(share.get("url").asText()).contains("/r/");

        // No Authorization header anywhere on this request - that is the point of the feature.
        MvcResult res = mockMvc.perform(get("/api/v1/public/replay/" + share.get("token").asText()))
                .andReturn();

        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = mapper.readTree(res.getResponse().getContentAsString());
        assertThat(body.get("roundId").asText()).isEqualTo(roundId);
        assertThat(body.get("matrixMatches").asBoolean()).isTrue();
        assertThat(body.get("totalWinMatches").asBoolean()).isTrue();
        assertThat(body.get("gameId").asText()).isEqualTo(GAME_ID);
        assertThat(body.get("currency").asText()).isEqualTo(CURRENCY);
        assertThat(body.get("rngDraws")).isNotEmpty();
        assertThat(body.get("steps")).isNotEmpty();
        // Every step re-derived and compared against what was persisted for it.
        assertThat(body.get("steps").get(0).get("matches").asBoolean()).isTrue();
    }

    @Test
    void thePublicPayloadCarriesNoPlayerOrSessionIdentity() throws Exception {
        JsonNode share = share(playOneRound("idem-public-2"));

        MvcResult res = mockMvc.perform(get("/api/v1/public/replay/" + share.get("token").asText()))
                .andReturn();

        JsonNode body = mapper.readTree(res.getResponse().getContentAsString());
        assertThat(body.has("playerId")).isFalse();
        assertThat(body.has("sessionId")).isFalse();
        // Belt and braces: the id must not be reachable anywhere in the serialised payload either.
        assertThat(res.getResponse().getContentAsString()).doesNotContain(PLAYER);
    }

    /**
     * A replay token is not a credential. It verifies against a key derived for replay links alone, so
     * presenting one as a bearer token fails authentication outright - a link forwarded to a stranger
     * cannot be turned into API access.
     */
    @Test
    void aReplayTokenCannotBeUsedAsABearerCredential() throws Exception {
        String roundId = playOneRound("idem-public-3");
        String replayToken = share(roundId).get("token").asText();

        MvcResult res = mockMvc.perform(post("/api/v1/admin/replay/" + roundId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + replayToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn();

        assertThat(res.getResponse().getStatus()).isEqualTo(401);
        assertThat(res.getResponse().getContentAsString()).contains("AUTH_FAILED");
    }

    /**
     * An expired link answers 410 with its own code, not 401 or 400 — the page keys off that to tell the
     * visitor the link ran out rather than that they did something wrong.
     *
     * <p>The token is minted by a throwaway service with a negative TTL. It shares the configured JWT
     * secret, so the key derivation lands on the same key and the signature verifies: what is being
     * tested here is the expiry branch, not a forgery.
     */
    @Test
    void anExpiredLinkAnswersGone() throws Exception {
        String roundId = playOneRound("idem-public-7");

        PublicReplayProperties elapsed = new PublicReplayProperties();
        elapsed.setPublicLinkTtl(Duration.ofSeconds(-60));
        String staleToken = new PublicReplayTokenService(securityProperties, elapsed).mint(roundId).token();

        MvcResult res = mockMvc.perform(get("/api/v1/public/replay/" + staleToken)).andReturn();

        assertThat(res.getResponse().getStatus()).isEqualTo(410);
        assertThat(res.getResponse().getContentAsString()).contains("REPLAY_LINK_EXPIRED");
    }

    @Test
    void anEditedLinkIsRejectedAsInvalid() throws Exception {
        String[] parts = share(playOneRound("idem-public-4")).get("token").asText().split("\\.");
        String forged = parts[0] + "." + parts[1] + ".c2lnbmF0dXJl";

        MvcResult res = mockMvc.perform(get("/api/v1/public/replay/" + forged)).andReturn();

        assertThat(res.getResponse().getStatus()).isEqualTo(400);
        assertThat(res.getResponse().getContentAsString()).contains("REPLAY_LINK_INVALID");
    }

    @Test
    void mintingALinkRequiresAdmin() throws Exception {
        String roundId = playOneRound("idem-public-5");

        MvcResult res = mockMvc.perform(post("/api/v1/admin/replay/" + roundId + "/share")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + playerJwt())
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn();

        assertThat(res.getResponse().getStatus()).isEqualTo(403);
        assertThat(res.getResponse().getContentAsString()).contains("FORBIDDEN_ACTION");
    }

    /**
     * The shareable URL has to resolve to the page. A JWT is full of dots, so this mainly pins that the
     * {@code /r/{token}} route captures the whole segment rather than truncating at the first one.
     *
     * <p>Asserted on the forwarded URL rather than the body: MockMvc records a forward instead of
     * executing it, so the static file is never rendered here. That the file exists and loads is covered
     * by running the app, not by this test.
     */
    @Test
    void theSharedUrlForwardsToTheReplayPage() throws Exception {
        String token = share(playOneRound("idem-public-6")).get("token").asText();
        assertThat(token).contains(".");

        MvcResult res = mockMvc.perform(get("/r/" + token)).andReturn();

        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        assertThat(res.getResponse().getForwardedUrl()).isEqualTo("/replay.html");
    }

    // ------------------------------------------------------------------ helpers

    /** Spin once and return the persisted round id. */
    private String playOneRound(String idemKey) throws Exception {
        JsonNode init = postJson("/api/v1/slot/init", null, playerJwt(),
                mapper.createObjectNode().put("gameId", GAME_ID).put("currency", CURRENCY).toString());
        String body = mapper.createObjectNode()
                .put("gameId", GAME_ID)
                .put("sessionId", init.get("sessionId").asText())
                .put("sessionVersion", init.get("sessionVersion").asLong())
                .put("betSize", "1.00")
                .put("powerBetActive", false)
                .toString();
        return postJson("/api/v1/slot/spin", idemKey, playerJwt(), body).get("roundId").asText();
    }

    private JsonNode share(String roundId) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/admin/replay/" + roundId + "/share")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + JwtTestFactory.adminToken("p-admin"))
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        return mapper.readTree(res.getResponse().getContentAsString());
    }

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
