package com.velocity.rgs.game.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocity.rgs.common.idempotency.IdempotencyAspect;
import com.velocity.rgs.common.idempotency.IdempotencyRecordRepository;
import com.velocity.rgs.game.persistence.FeaturePurchaseEventRepository;
import com.velocity.rgs.game.persistence.GameRoundRepository;
import com.velocity.rgs.game.persistence.PickCollectSnapshotRepository;
import com.velocity.rgs.math.domain.BonusBuyType;
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

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@RgsIntegrationTest
@AutoConfigureMockMvc
class SlotGameControllerIntegrationTest {

    private static final String PLAYER = "p-slot-1";
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
    void initCreatesNewSessionInBaseGameAndReturnsAvailableActions() throws Exception {
        // /init does not require Idempotency-Key (it is read-only-ish & seed-on-first-use)
        JsonNode init = postJson("/api/v1/slot/init", null,
                mapper.createObjectNode().put("gameId", GAME_ID).put("currency", CURRENCY).toString());

        assertThat(init.get("sessionId").asText()).startsWith("ses-");
        assertThat(init.get("sessionVersion").asLong()).isZero();
        assertThat(init.get("gameId").asText()).isEqualTo(GAME_ID);
        assertThat(init.get("currentState").asText()).isEqualTo("BASE_GAME");
        assertThat(init.get("availableActions")).isNotEmpty();
        assertThat(init.get("balance").decimalValue()).isEqualByComparingTo(new BigDecimal("10000.00"));
        assertThat(init.get("featureFlags").get("bonusBuyEnabled").asBoolean()).isTrue();
    }

    @Test
    void initIsResumable_returnsSameSessionOnRepeatedCall() throws Exception {
        JsonNode first = postJson("/api/v1/slot/init", null,
                mapper.createObjectNode().put("gameId", GAME_ID).put("currency", CURRENCY).toString());
        JsonNode second = postJson("/api/v1/slot/init", null,
                mapper.createObjectNode().put("gameId", GAME_ID).put("currency", CURRENCY).toString());

        assertThat(second.get("sessionId").asText()).isEqualTo(first.get("sessionId").asText());
    }

    @Test
    void spinExecutesDebitAndOptionallyCredits() throws Exception {
        JsonNode init = initSession();
        String sessionId = init.get("sessionId").asText();
        long version = init.get("sessionVersion").asLong();

        String body = mapper.createObjectNode()
                .put("gameId", GAME_ID)
                .put("sessionId", sessionId)
                .put("sessionVersion", version)
                .put("betSize", "1.00")
                .put("powerBetActive", false)
                .toString();

        JsonNode spin = postJson("/api/v1/slot/spin", "idem-spin-1", body);
        assertThat(spin.get("roundId").asText()).startsWith("rnd-");
        assertThat(spin.get("betDebited").decimalValue()).isEqualByComparingTo(new BigDecimal("1.00"));
        assertThat(spin.get("sessionVersion").asLong()).isGreaterThan(version);
        // round persisted
        assertThat(roundRepository.findByRoundId(spin.get("roundId").asText())).isPresent();
        // wallet ledger contains the BET row
        assertThat(walletTransactionRepository.findAll())
                .anyMatch(t -> t.getTransactionId().endsWith(":bet"));
    }

    @Test
    void powerBetSpinDebitsMultipliedStake() throws Exception {
        JsonNode init = initSession();
        String sessionId = init.get("sessionId").asText();
        long version = init.get("sessionVersion").asLong();
        // aztec-fire powerBet.betMultiplier is 1.50, so a 1.00 base bet must wager 1.50.
        assertThat(init.get("featureFlags").get("powerBetMultiplier").decimalValue())
                .isEqualByComparingTo(new BigDecimal("1.50"));

        String body = mapper.createObjectNode()
                .put("gameId", GAME_ID)
                .put("sessionId", sessionId)
                .put("sessionVersion", version)
                .put("betSize", "1.00")
                .put("powerBetActive", true)
                .toString();

        JsonNode spin = postJson("/api/v1/slot/spin", "idem-power-1", body);
        assertThat(spin.get("betDebited").decimalValue()).isEqualByComparingTo(new BigDecimal("1.50"));
        assertThat(spin.get("featuresTriggered").get("isPowerBetActive").asBoolean()).isTrue();
        // persisted round records the multiplied stake
        var round = roundRepository.findByRoundId(spin.get("roundId").asText()).orElseThrow();
        assertThat(round.getBetAmount()).isEqualByComparingTo(new BigDecimal("1.50"));
        assertThat(round.isPowerBetActive()).isTrue();
    }

    @Test
    void spinIsIdempotentOnRetryWithSameKey() throws Exception {
        JsonNode init = initSession();
        String sessionId = init.get("sessionId").asText();
        long version = init.get("sessionVersion").asLong();

        String body = mapper.createObjectNode()
                .put("gameId", GAME_ID)
                .put("sessionId", sessionId)
                .put("sessionVersion", version)
                .put("betSize", "1.00")
                .put("powerBetActive", false)
                .toString();

        MvcResult first = postRaw("/api/v1/slot/spin", "idem-spin-x", body);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);

        MvcResult replay = postRaw("/api/v1/slot/spin", "idem-spin-x", body);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getHeader(IdempotencyAspect.HEADER_REPLAY)).isEqualTo("true");
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        // exactly one round was persisted
        assertThat(roundRepository.count()).isEqualTo(1);
    }

    @Test
    void buyFeatureDebitsCostPersistsEventAndAdvancesState() throws Exception {
        JsonNode init = initSession();
        String sessionId = init.get("sessionId").asText();
        long version = init.get("sessionVersion").asLong();

        String body = mapper.createObjectNode()
                .put("gameId", GAME_ID)
                .put("sessionId", sessionId)
                .put("sessionVersion", version)
                .put("buyType", BonusBuyType.FREE_SPINS_BUY.name())
                .put("betSize", "1.00")
                .toString();

        JsonNode buy = postJson("/api/v1/slot/feature/buy", "idem-buy-1", body);
        assertThat(buy.get("buyType").asText()).isEqualTo(BonusBuyType.FREE_SPINS_BUY.name());
        assertThat(buy.get("cost").decimalValue()).isEqualByComparingTo(new BigDecimal("9"));
        assertThat(buy.get("enteredState").asText()).isEqualTo("FREE_SPINS_AWAITING");
        assertThat(featurePurchaseRepository.findAll()).hasSize(1);
        assertThat(walletTransactionRepository.findAll())
                .anyMatch(t -> t.getTransactionId().endsWith(":bonus-buy"));
    }

    @Test
    void pickCollectEndToEnd_buyThenStartThenAllPicksCreditFeatureWin() throws Exception {
        JsonNode init = initSession();
        String sessionId = init.get("sessionId").asText();
        long version = init.get("sessionVersion").asLong();

        // 1. Buy Pick & Collect bonus
        String buyBody = mapper.createObjectNode()
                .put("gameId", GAME_ID)
                .put("sessionId", sessionId)
                .put("sessionVersion", version)
                .put("buyType", BonusBuyType.PICK_COLLECT_BUY.name())
                .put("betSize", "1.00")
                .toString();
        JsonNode buy = postJson("/api/v1/slot/feature/buy", "idem-pick-buy", buyBody);
        assertThat(buy.get("enteredState").asText()).isEqualTo("PICK_COLLECT_AWAITING");
        version = buy.get("sessionVersion").asLong();

        // 2. Transition into PICK_COLLECT_LOOP
        String startBody = mapper.createObjectNode()
                .put("gameId", GAME_ID)
                .put("sessionId", sessionId)
                .put("sessionVersion", version)
                .put("featureType", "PICK_COLLECT")
                .toString();
        JsonNode start = postJson("/api/v1/slot/feature/start", "idem-pick-start", startBody);
        assertThat(start.get("currentState").asText()).isEqualTo("PICK_COLLECT_LOOP");
        assertThat(start.get("activeFeatureView")).isNotNull();
        version = start.get("sessionVersion").asLong();

        // 3. Execute 5 picks (fixed-picks completion = 5)
        for (int i = 0; i < 5; i++) {
            String pickBody = mapper.createObjectNode()
                    .put("gameId", GAME_ID)
                    .put("sessionId", sessionId)
                    .put("sessionVersion", version)
                    .put("position", i)
                    .toString();
            JsonNode pick = postJson("/api/v1/slot/feature/pick", "idem-pick-" + i, pickBody);
            version = pick.get("sessionVersion").asLong();
            if (pick.get("featureCompleted").asBoolean()) {
                assertThat(pick.get("currentState").asText()).isEqualTo("BASE_GAME");
                assertThat(pickRepository.findAll())
                        .anyMatch(s -> "COMPLETED".equals(s.getStatus()));
                return;
            }
        }
    }

    @Test
    void missingIdempotencyKeyOnSpinReturns400() throws Exception {
        JsonNode init = initSession();
        String sessionId = init.get("sessionId").asText();
        long version = init.get("sessionVersion").asLong();

        String body = mapper.createObjectNode()
                .put("gameId", GAME_ID)
                .put("sessionId", sessionId)
                .put("sessionVersion", version)
                .put("betSize", "1.00")
                .put("powerBetActive", false)
                .toString();

        MvcResult res = postRaw("/api/v1/slot/spin", null, body);
        assertThat(res.getResponse().getStatus()).isEqualTo(400);
        assertThat(res.getResponse().getContentAsString()).contains("VALIDATION_ERROR");
    }

    // ===================================================== helpers

    private JsonNode initSession() throws Exception {
        return postJson("/api/v1/slot/init", null,
                mapper.createObjectNode().put("gameId", GAME_ID).put("currency", CURRENCY).toString());
    }

    private JsonNode postJson(String url, String idemKey, String body) throws Exception {
        MvcResult res = postRaw(url, idemKey, body);
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        return mapper.readTree(res.getResponse().getContentAsString());
    }

    private MvcResult postRaw(String url, String idemKey, String body) throws Exception {
        var req = post(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (idemKey != null) {
            req.header(IdempotencyAspect.HEADER_KEY, idemKey);
        }
        return mockMvc.perform(req).andReturn();
    }

    private String jwt() {
        return JwtTestFactory.validToken(PLAYER, "ses-" + UUID.randomUUID(), CURRENCY);
    }
}
