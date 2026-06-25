package com.velocity.rgs.blackjack.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocity.rgs.blackjack.persistence.BlackjackRoundRepository;
import com.velocity.rgs.common.idempotency.IdempotencyAspect;
import com.velocity.rgs.common.idempotency.IdempotencyRecordRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@RgsIntegrationTest
@AutoConfigureMockMvc
class BlackjackControllerIntegrationTest {

    private static final String PLAYER = "p-blackjack-1";
    private static final String CURRENCY = "EUR";
    private static final String GAME_ID = "classic-blackjack";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private GameSessionRepository sessionRepository;
    @Autowired private SessionStore sessionStore;
    @Autowired private BlackjackRoundRepository roundRepository;
    @Autowired private WalletBalanceRepository walletBalanceRepository;
    @Autowired private WalletTransactionRepository walletTransactionRepository;
    @Autowired private IdempotencyRecordRepository idempotencyRepository;

    @BeforeEach
    void clean() {
        roundRepository.deleteAll();
        idempotencyRepository.deleteAll();
        sessionRepository.deleteAll();
        walletTransactionRepository.deleteAll();
        walletBalanceRepository.deleteAll();
        sessionStore.evict(PLAYER);
    }

    @Test
    void initCreatesSessionAndReturnsRulesAndActions() throws Exception {
        JsonNode init = initSession();
        assertThat(init.get("sessionId").asText()).startsWith("ses-");
        assertThat(init.get("gameId").asText()).isEqualTo(GAME_ID);
        assertThat(init.get("balance").decimalValue()).isEqualByComparingTo(new BigDecimal("10000.00"));
        assertThat(init.get("betValues")).isNotEmpty();
        assertThat(init.get("defaultBet").decimalValue()).isEqualByComparingTo(new BigDecimal("5.00"));
        assertThat(init.get("maxBet").decimalValue()).isEqualByComparingTo(new BigDecimal("500.00"));
        assertThat(init.get("rules").get("decks").asInt()).isEqualTo(6);
        assertThat(init.get("rules").get("dealerHitsSoft17").asBoolean()).isFalse();
        assertThat(init.get("availableActions").toString()).contains("DEAL");
        assertThat(init.get("activeRound").isNull()).isTrue();
    }

    @Test
    void dealStartsRoundDebitsAndNeverLeaksTheHoleCard() throws Exception {
        JsonNode init = initSession();
        JsonNode deal = deal(init, "5.00", "idem-deal-1");

        assertThat(deal.get("roundId").asText()).startsWith("blk-");
        assertThat(deal.get("totalBet").decimalValue()).isEqualByComparingTo(new BigDecimal("5.00"));
        assertThat(deal.get("playerHands").get(0).get("cards")).hasSize(2);
        // round persisted + wallet BET row written
        assertThat(roundRepository.findByRoundId(deal.get("roundId").asText())).isPresent();
        assertThat(walletTransactionRepository.findAll())
                .anyMatch(t -> t.getTransactionId().endsWith(":bet"));

        // While the round is live, only the dealer's upcard is exposed - the hole card is never serialized.
        if ("IN_PROGRESS".equals(deal.get("status").asText())) {
            JsonNode dealer = deal.get("dealer");
            assertThat(dealer.get("hidden").asBoolean()).isTrue();
            assertThat(dealer.get("cards")).hasSize(1);
            assertThat(dealer.get("value").isNull()).isTrue();
        }
    }

    @Test
    void playToSettlementCreditsAndAppearsInUnifiedHistory() throws Exception {
        JsonNode init = initSession();
        JsonNode round = deal(init, "5.00", "idem-deal-2");
        long version = round.get("sessionVersion").asLong();

        int guard = 0;
        while ("IN_PROGRESS".equals(round.get("status").asText()) && guard++ < 8) {
            round = action(version, "STAND", "idem-stand-" + guard);
            version = round.get("sessionVersion").asLong();
        }
        assertThat(round.get("status").asText()).isEqualTo("SETTLED");
        assertThat(round.get("dealer").get("hidden").asBoolean()).isFalse();
        assertThat(round.get("totalWin")).isNotNull();
        String roundId = round.get("roundId").asText();

        MvcResult res = mockMvc.perform(get("/api/v1/admin/rounds/" + PLAYER)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + JwtTestFactory.adminToken(PLAYER))).andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        JsonNode rounds = mapper.readTree(res.getResponse().getContentAsString());
        assertThat(rounds).anyMatch(r -> roundId.equals(r.get("roundId").asText())
                && GAME_ID.equals(r.get("gameId").asText())
                && "BLACKJACK".equals(r.get("stateContext").asText()));
    }

    @Test
    void dealIsIdempotentOnRetryWithSameKey() throws Exception {
        JsonNode init = initSession();
        String body = dealBody(init.get("sessionId").asText(), init.get("sessionVersion").asLong(), "5.00");

        MvcResult first = postRaw("/api/v1/blackjack/deal", "idem-deal-x", body);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);

        MvcResult replay = postRaw("/api/v1/blackjack/deal", "idem-deal-x", body);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getHeader(IdempotencyAspect.HEADER_REPLAY)).isEqualTo("true");
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(roundRepository.count()).isEqualTo(1);
    }

    @Test
    void actionIsIdempotentOnRetryWithSameKey() throws Exception {
        JsonNode init = initSession();
        JsonNode round = dealUntilInProgress(init);
        long version = round.get("sessionVersion").asLong();
        String body = actionBody(version, "STAND");

        MvcResult first = postRaw("/api/v1/blackjack/action", "idem-act-x", body);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);

        MvcResult replay = postRaw("/api/v1/blackjack/action", "idem-act-x", body);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getHeader(IdempotencyAspect.HEADER_REPLAY)).isEqualTo("true");
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
    }

    @Test
    void doubleDownDebitsExtraAndSettles() throws Exception {
        JsonNode init = initSession();
        long version = init.get("sessionVersion").asLong();
        JsonNode doubleable = null;
        for (int i = 0; i < 25 && doubleable == null; i++) {
            JsonNode r = postJson("/api/v1/blackjack/deal", "idem-dd-" + i,
                    dealBody(init.get("sessionId").asText(), version, "5.00"));
            version = r.get("sessionVersion").asLong();
            if ("IN_PROGRESS".equals(r.get("status").asText())
                    && r.get("availableActions").toString().contains("DOUBLE")) {
                doubleable = r;
                break;
            }
            int guard = 0;
            while ("IN_PROGRESS".equals(r.get("status").asText()) && guard++ < 8) {
                r = action(version, "STAND", "idem-dd-stand-" + i + "-" + guard);
                version = r.get("sessionVersion").asLong();
            }
        }
        assertThat(doubleable).as("dealt a doubleable hand within 25 tries").isNotNull();

        JsonNode settled = action(version, "DOUBLE", "idem-double-go");
        assertThat(settled.get("status").asText()).isEqualTo("SETTLED");
        // the doubled hand staked 2x base
        assertThat(settled.get("totalBet").decimalValue()).isEqualByComparingTo(new BigDecimal("10.00"));
    }

    @Test
    void rejectsOffGridBet() throws Exception {
        JsonNode init = initSession();
        MvcResult res = postRaw("/api/v1/blackjack/deal", "idem-offgrid",
                dealBody(init.get("sessionId").asText(), init.get("sessionVersion").asLong(), "0.37"));
        assertThat(res.getResponse().getStatus()).isEqualTo(400);
        assertThat(res.getResponse().getContentAsString()).contains("VALIDATION_ERROR");
        assertThat(roundRepository.count()).isZero();
    }

    @Test
    void rejectsActionWithNoRoundInProgress() throws Exception {
        JsonNode init = initSession();
        MvcResult res = postRaw("/api/v1/blackjack/action", "idem-noround",
                actionBody(init.get("sessionVersion").asLong(), "STAND"));
        assertThat(res.getResponse().getStatus()).isEqualTo(400);
        assertThat(res.getResponse().getContentAsString()).contains("VALIDATION_ERROR");
    }

    @Test
    void rejectsUnknownAction() throws Exception {
        JsonNode init = initSession();
        MvcResult res = postRaw("/api/v1/blackjack/action", "idem-badaction",
                actionBody(init.get("sessionVersion").asLong(), "FLY"));
        assertThat(res.getResponse().getStatus()).isEqualTo(400);
        assertThat(res.getResponse().getContentAsString()).contains("VALIDATION_ERROR");
    }

    @Test
    void insufficientBalanceIsRejectedAndPersistsNoRound() throws Exception {
        JsonNode init = initSession();
        postJsonAdmin("/api/v1/admin/wallet/balance",
                mapper.createObjectNode().put("playerId", PLAYER).put("currency", CURRENCY)
                        .put("balance", "1.00").toString());

        MvcResult res = postRaw("/api/v1/blackjack/deal", "idem-broke",
                dealBody(init.get("sessionId").asText(), init.get("sessionVersion").asLong(), "5.00"));
        assertThat(res.getResponse().getStatus()).isGreaterThanOrEqualTo(400);
        assertThat(roundRepository.count()).isZero();
    }

    // ===================================================== helpers

    private JsonNode initSession() throws Exception {
        return postJson("/api/v1/blackjack/init", null,
                mapper.createObjectNode().put("gameId", GAME_ID).put("currency", CURRENCY).toString());
    }

    private JsonNode deal(JsonNode init, String bet, String idemKey) throws Exception {
        return postJson("/api/v1/blackjack/deal", idemKey,
                dealBody(init.get("sessionId").asText(), init.get("sessionVersion").asLong(), bet));
    }

    private JsonNode action(long version, String act, String idemKey) throws Exception {
        return postJson("/api/v1/blackjack/action", idemKey, actionBody(version, act));
    }

    /** Deal repeatedly (each settled natural is rare) until a live round is dealt, for action-level tests. */
    private JsonNode dealUntilInProgress(JsonNode init) throws Exception {
        long version = init.get("sessionVersion").asLong();
        for (int i = 0; i < 10; i++) {
            JsonNode round = postJson("/api/v1/blackjack/deal", "idem-deal-loop-" + i,
                    dealBody(init.get("sessionId").asText(), version, "5.00"));
            if ("IN_PROGRESS".equals(round.get("status").asText())) {
                return round;
            }
            version = round.get("sessionVersion").asLong();
        }
        throw new IllegalStateException("Could not deal an in-progress round in 10 tries");
    }

    private String dealBody(String sessionId, long version, String bet) {
        return mapper.createObjectNode()
                .put("gameId", GAME_ID)
                .put("sessionId", sessionId)
                .put("sessionVersion", version)
                .put("bet", new BigDecimal(bet))
                .toString();
    }

    private String actionBody(long version, String action) {
        return mapper.createObjectNode()
                .put("gameId", GAME_ID)
                .put("sessionId", currentSessionId())
                .put("sessionVersion", version)
                .put("action", action)
                .toString();
    }

    private String currentSessionId() {
        return sessionStore.findByPlayerId(PLAYER).orElseThrow().getSessionId();
    }

    private JsonNode postJson(String url, String idemKey, String body) throws Exception {
        MvcResult res = postRaw(url, idemKey, body);
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        return mapper.readTree(res.getResponse().getContentAsString());
    }

    private void postJsonAdmin(String url, String body) throws Exception {
        MvcResult res = mockMvc.perform(post(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + JwtTestFactory.adminToken(PLAYER))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)).andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
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
