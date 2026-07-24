package com.velocity.rgs.rg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocity.rgs.common.idempotency.IdempotencyAspect;
import com.velocity.rgs.common.idempotency.IdempotencyRecordRepository;
import com.velocity.rgs.rg.persistence.RgLimitRepository;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Responsible Gaming enforcement end to end (§4.2).
 *
 * <p>RG is off in the shared test profile because the shipped defaults are tight enough to fire inside
 * a 60-second demo, which would also make them fire partway through any test that plays a few dozen
 * rounds. This class turns it on for itself, so the feature is covered without being silently applied
 * to tests measuring something else.
 */
@RgsIntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "rgs.rg.enabled=true",
        // Nothing is inherited from the shipped ruleset: every limit this class relies on is set
        // explicitly through the API, so a change to the demo defaults cannot quietly rewrite what
        // these tests assert.
        "rgs.rg.defaults.session-limit-minutes=600",
        "rgs.rg.defaults.loss-limit=100000.00",
        "rgs.rg.defaults.wager-limit=100000.00",
        "rgs.rg.defaults.reality-check-minutes=120"
})
class RgPolicyIntegrationTest {

    private static final String PLAYER = "p-rg";
    private static final String CURRENCY = "EUR";
    private static final String GAME = "aztec-fire";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private RgLimitRepository rgLimitRepository;
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
        rgLimitRepository.deleteAll();
        roundRepository.deleteAll();
        featurePurchaseRepository.deleteAll();
        pickRepository.deleteAll();
        idempotencyRepository.deleteAll();
        sessionRepository.deleteAll();
        walletTransactionRepository.deleteAll();
        walletBalanceRepository.deleteAll();
        sessionStore.evict(PLAYER);
    }

    // ---------------------------------------------------------------- limits bite

    /**
     * The headline behaviour: a stake that would cross the loss limit is refused, and the error names
     * which limit fired and what it is set to. A generic failure here would be indistinguishable from
     * the server being broken.
     */
    @Test
    void aStakeThatWouldCrossTheLossLimitIsRefusedByName() throws Exception {
        setLimits("{\"lossLimit\":1.00}");
        String sessionId = initSlot();

        MvcResult res = spinRaw(sessionId, "5.00", "over-loss");

        assertThat(res.getResponse().getStatus()).isEqualTo(409);
        JsonNode body = mapper.readTree(res.getResponse().getContentAsString());
        assertThat(body.get("code").asText()).isEqualTo("RG_LIMIT_EXCEEDED");
        assertThat(body.path("context").path("limit").asText()).isEqualTo("LOSS");
        assertThat(body.path("context").path("limitValue").asText()).isEqualTo("1.00");
        assertThat(body.path("context").path("currency").asText()).isEqualTo(CURRENCY);
    }

    @Test
    void aStakeThatWouldCrossTheWagerLimitIsRefusedByName() throws Exception {
        setLimits("{\"wagerLimit\":2.00}");
        String sessionId = initSlot();

        JsonNode body = mapper.readTree(
                spinRaw(sessionId, "5.00", "over-wager").getResponse().getContentAsString());

        assertThat(body.get("code").asText()).isEqualTo("RG_LIMIT_EXCEEDED");
        assertThat(body.path("context").path("limit").asText()).isEqualTo("WAGER");
    }

    /**
     * The limit is enforced against the wallet ledger, not a counter, so consumption accrued by real
     * play is what stops the next stake - the case a hand-set limit alone would never exercise.
     */
    @Test
    void consumptionFromRealPlayIsWhatStopsTheNextStake() throws Exception {
        setLimits("{\"wagerLimit\":12.00}");
        String sessionId = initSlot();

        // 12.00 of headroom at 5.00 a spin: two land, the third would reach 15.00 and is refused.
        assertThat(spinRaw(sessionId, "5.00", "w1").getResponse().getStatus()).isEqualTo(200);
        assertThat(spinRaw(sessionId, "5.00", "w2").getResponse().getStatus()).isEqualTo(200);

        MvcResult third = spinRaw(sessionId, "5.00", "w3");
        assertThat(third.getResponse().getStatus()).isEqualTo(409);
        assertThat(third.getResponse().getContentAsString()).contains("WAGER");

        // canPlay stays true, and that is not a contradiction: 2.00 of headroom remains, so play is
        // available at some stake even though the 5.00 one just refused does not fit. What is blocked
        // is a specific stake, and that is the action list's job to reflect, not this flag's.
        JsonNode status = status();
        assertThat(status.get("wagered").decimalValue()).isEqualByComparingTo("10.00");
        assertThat(status.get("canPlay").asBoolean()).isTrue();
    }

    /**
     * The spin button dies because the server stopped offering the action. The client is not deciding
     * anything - it renders an action list that no longer contains SPIN.
     */
    @Test
    void availableActionsLosesSpinOnceALimitIsReached() throws Exception {
        setLimits("{\"wagerLimit\":6.00}");
        String sessionId = initSlot();

        JsonNode spin = mapper.readTree(
                spinRaw(sessionId, "5.00", "last-legal").getResponse().getContentAsString());

        assertThat(spin.get("availableActions")).isNotNull();
        assertThat(actionsOf(spin)).doesNotContain("SPIN");
    }

    /**
     * A player with no stake left to make is not offered SPIN when they open the game, rather than
     * being let in and refused on the first click.
     *
     * <p>"No stake left" means exactly that, and the probe is the game's <em>minimum</em> bet: a player
     * whose 5.00 spin no longer fits but whose 0.20 one does is not blocked, and hiding the button from
     * them would be the client lying about what the server would accept. So the limit here is consumed
     * to the point where even 0.20 is refused.
     */
    @Test
    void initWithholdsSpinOnceNoStakeAtAllWouldBeAccepted() throws Exception {
        String sessionId = initSlot();
        setLimits("{\"wagerLimit\":100.00}");
        assertThat(spinRaw(sessionId, "5.00", "consume").getResponse().getStatus()).isEqualTo(200);

        // 5.00 staked, and the limit now sits exactly there - even the 0.20 minimum would cross it.
        setLimits("{\"wagerLimit\":5.00}");

        JsonNode init = postJson("/api/v1/slot/init", null, initBody());

        assertThat(actionsOf(init)).doesNotContain("SPIN", "BUY_FEATURE");
    }

    /** The other half of that rule: a stake that still fits keeps the button alive. */
    @Test
    void initKeepsSpinWhenTheMinimumStakeStillFits() throws Exception {
        setLimits("{\"lossLimit\":1.00}");
        initSlot();

        JsonNode init = postJson("/api/v1/slot/init", null, initBody());

        assertThat(actionsOf(init))
                .as("0.20 is still playable under a 1.00 loss limit, so SPIN must stay offered")
                .contains("SPIN");
    }

    // ---------------------------------------------------------------- never strand a paid feature

    /**
     * The rule that separates protecting a player from penalising one. A free spin was paid for by the
     * round that triggered it, so a limit reached mid-feature must not strand it - the money is already
     * gone and the feature is what it bought. The limit stops the next <em>stake</em>, not the round in
     * flight.
     */
    @Test
    void aLimitReachedMidFeatureDoesNotStrandTheFreeSpinsAlreadyPaidFor() throws Exception {
        String sessionId = initSlot();
        long version = version(postJson("/api/v1/slot/init", null, initBody()));

        JsonNode buy = postJson("/api/v1/slot/feature/buy", "buy-" + UUID.randomUUID(),
                mapper.createObjectNode()
                        .put("gameId", GAME).put("sessionId", sessionId)
                        .put("sessionVersion", version)
                        .put("buyType", "FREE_SPINS_BUY").put("betSize", "1.00").toString());
        JsonNode start = postJson("/api/v1/slot/feature/start", "start-" + UUID.randomUUID(),
                mapper.createObjectNode()
                        .put("gameId", GAME).put("sessionId", sessionId)
                        .put("sessionVersion", buy.get("sessionVersion").asLong())
                        .put("featureType", "FREE_SPINS").toString());

        // The buy has already cost 100x; now clamp the limit far below what has been staked.
        setLimits("{\"lossLimit\":1.00,\"wagerLimit\":1.00}");

        MvcResult freeSpin = spinRaw(sessionId, "1.00", "fs-after-limit",
                start.get("sessionVersion").asLong());

        assertThat(freeSpin.getResponse().getStatus())
                .as("a free spin already paid for must not be refused: %s",
                        freeSpin.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    // ---------------------------------------------------------------- cool-off and self-exclusion

    @Test
    void aCoolOffBlocksPlayAndSaysWhenItEnds() throws Exception {
        String sessionId = initSlot();
        postJson("/api/v1/rg/cool-off", null, "{\"hours\":24}");

        JsonNode body = mapper.readTree(
                spinRaw(sessionId, "5.00", "cooling").getResponse().getContentAsString());

        assertThat(body.get("code").asText()).isEqualTo("RG_LIMIT_EXCEEDED");
        assertThat(body.path("context").path("limit").asText()).isEqualTo("COOL_OFF");
        assertThat(body.path("context").path("resetsAt").asText()).isNotBlank();
    }

    /** A break can be extended but never shortened - the one direction it must not move. */
    @Test
    void aCoolOffCannotBeShortened() throws Exception {
        JsonNode longBreak = postJson("/api/v1/rg/cool-off", null, "{\"hours\":48}");
        JsonNode shortened = postJson("/api/v1/rg/cool-off", null, "{\"hours\":1}");

        // Truncated to milliseconds because the first response carries the in-memory Instant and the
        // second has been through Postgres, which stores microseconds. Same instant, different nanos.
        assertThat(java.time.Instant.parse(shortened.get("blockedUntil").asText())
                        .truncatedTo(java.time.temporal.ChronoUnit.MILLIS))
                .isEqualTo(java.time.Instant.parse(longBreak.get("blockedUntil").asText())
                        .truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
    }

    /**
     * Self-exclusion is a different thing to a limit and answers with a different code and status: 403
     * and final, versus 409 and recoverable. Collapsing them would render a permanent block as a
     * transient error with a retry.
     */
    @Test
    void selfExclusionBlocksPlayWithItsOwnCode() throws Exception {
        String sessionId = initSlot();
        postJson("/api/v1/rg/self-exclude", null, "{\"confirm\":\"SELF-EXCLUDE\"}");

        MvcResult res = spinRaw(sessionId, "5.00", "excluded");

        assertThat(res.getResponse().getStatus()).isEqualTo(403);
        assertThat(mapper.readTree(res.getResponse().getContentAsString()).get("code").asText())
                .isEqualTo("RG_SELF_EXCLUDED");
    }

    /** The most consequential call in the API does not fire on a bare request. */
    @Test
    void selfExclusionRequiresTypedConfirmation() throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/rg/self-exclude")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirm\":\"yes\"}"))
                .andReturn();

        assertThat(res.getResponse().getStatus()).isEqualTo(400);
        assertThat(status().get("selfExcluded").asBoolean()).isFalse();
    }

    /** And a self-excluded player cannot quietly undo it by setting a limit. */
    @Test
    void aSelfExcludedPlayerCannotChangeTheirLimits() throws Exception {
        postJson("/api/v1/rg/self-exclude", null, "{\"confirm\":\"SELF-EXCLUDE\"}");

        MvcResult res = mockMvc.perform(put("/api/v1/rg/limits")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lossLimit\":9999.00}"))
                .andReturn();

        assertThat(res.getResponse().getStatus()).isEqualTo(403);
    }

    // ---------------------------------------------------------------- panel surface

    @Test
    void statusReportsLimitsAlongsideConsumption() throws Exception {
        setLimits("{\"lossLimit\":80.00,\"wagerLimit\":200.00,\"sessionLimitMinutes\":45}");
        String sessionId = initSlot();
        spinRaw(sessionId, "5.00", "one");

        JsonNode status = status();

        assertThat(status.get("lossLimit").decimalValue()).isEqualByComparingTo("80.00");
        assertThat(status.get("wagerLimit").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(status.get("sessionLimitMinutes").asInt()).isEqualTo(45);
        assertThat(status.get("wagered").decimalValue()).isEqualByComparingTo("5.00");
        assertThat(status.get("canPlay").asBoolean()).isTrue();
    }

    /** A limit may always be tightened; the ruleset only caps how loose one can be made. */
    @Test
    void aLimitCannotBeSetBeyondTheRulesetsCeiling() throws Exception {
        MvcResult res = mockMvc.perform(put("/api/v1/rg/limits")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lossLimit\":999999999.00}"))
                .andReturn();

        assertThat(res.getResponse().getStatus()).isEqualTo(400);
    }

    /** The demo escape hatch, without which self-exclusion makes the feature a one-shot. */
    @Test
    void theDevResetClearsEveryBlock() throws Exception {
        postJson("/api/v1/rg/self-exclude", null, "{\"confirm\":\"SELF-EXCLUDE\"}");
        assertThat(status().get("selfExcluded").asBoolean()).isTrue();

        postJson("/api/v1/rg/dev/reset", null, null);

        JsonNode after = status();
        assertThat(after.get("selfExcluded").asBoolean()).isFalse();
        assertThat(after.get("canPlay").asBoolean()).isTrue();
    }

    // ---------------------------------------------------------------- helpers

    private String initSlot() throws Exception {
        return postJson("/api/v1/slot/init", null, initBody()).get("sessionId").asText();
    }

    private String initBody() {
        return mapper.createObjectNode().put("gameId", GAME).put("currency", CURRENCY).toString();
    }

    private JsonNode status() throws Exception {
        MvcResult res = mockMvc.perform(get("/api/v1/rg/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token()))
                .andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        return mapper.readTree(res.getResponse().getContentAsString());
    }

    private void setLimits(String json) throws Exception {
        MvcResult res = mockMvc.perform(put("/api/v1/rg/limits")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn();
        assertThat(res.getResponse().getStatus())
                .as("%s", res.getResponse().getContentAsString()).isEqualTo(200);
    }

    /** A spin that is allowed to fail, so the RG refusal itself can be asserted on. */
    private MvcResult spinRaw(String sessionId, String bet, String idem) throws Exception {
        long version = version(postJson("/api/v1/slot/init", null, initBody()));
        return spinRaw(sessionId, bet, idem, version);
    }

    private MvcResult spinRaw(String sessionId, String bet, String idem, long version) throws Exception {
        return mockMvc.perform(post("/api/v1/slot/spin")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                        .header(IdempotencyAspect.HEADER_KEY, idem + "-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.createObjectNode()
                                .put("gameId", GAME)
                                .put("sessionId", sessionId)
                                .put("sessionVersion", version)
                                .put("betSize", bet)
                                .put("powerBetActive", false)
                                .toString()))
                .andReturn();
    }

    private static long version(JsonNode node) {
        return node.get("sessionVersion").asLong();
    }

    private static java.util.List<String> actionsOf(JsonNode response) {
        java.util.List<String> actions = new java.util.ArrayList<>();
        response.path("availableActions").forEach(a -> actions.add(a.asText()));
        return actions;
    }

    private String token() {
        return JwtTestFactory.validToken(PLAYER, "ses-" + UUID.randomUUID(), CURRENCY);
    }

    private JsonNode postJson(String url, String idemKey, String body) throws Exception {
        var req = post(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                .contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            req.content(body);
        }
        if (idemKey != null) {
            req.header(IdempotencyAspect.HEADER_KEY, idemKey);
        }
        MvcResult res = mockMvc.perform(req).andReturn();
        assertThat(res.getResponse().getStatus())
                .as("%s -> %s", url, res.getResponse().getContentAsString())
                .isEqualTo(200);
        return mapper.readTree(res.getResponse().getContentAsString());
    }
}
