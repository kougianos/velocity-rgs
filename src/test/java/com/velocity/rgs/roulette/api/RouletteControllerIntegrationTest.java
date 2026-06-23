package com.velocity.rgs.roulette.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.velocity.rgs.common.idempotency.IdempotencyAspect;
import com.velocity.rgs.common.idempotency.IdempotencyRecordRepository;
import com.velocity.rgs.roulette.persistence.RouletteRoundRepository;
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
class RouletteControllerIntegrationTest {

    private static final String PLAYER = "p-roulette-1";
    private static final String CURRENCY = "EUR";
    private static final String GAME_ID = "european-roulette";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private GameSessionRepository sessionRepository;
    @Autowired private SessionStore sessionStore;
    @Autowired private RouletteRoundRepository roundRepository;
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
    void initCreatesSessionAndReturnsStakesAndActions() throws Exception {
        JsonNode init = initSession();
        assertThat(init.get("sessionId").asText()).startsWith("ses-");
        assertThat(init.get("sessionVersion").asLong()).isZero();
        assertThat(init.get("gameId").asText()).isEqualTo(GAME_ID);
        assertThat(init.get("balance").decimalValue()).isEqualByComparingTo(new BigDecimal("10000.00"));
        assertThat(init.get("betValues")).isNotEmpty();
        assertThat(init.get("defaultBet").decimalValue()).isEqualByComparingTo(new BigDecimal("1.00"));
        assertThat(init.get("availableActions").toString()).contains("SPIN");
    }

    @Test
    void spinSettlesBetsDebitsAndPersistsRound() throws Exception {
        JsonNode init = initSession();
        String sessionId = init.get("sessionId").asText();
        long version = init.get("sessionVersion").asLong();

        JsonNode spin = postJson("/api/v1/roulette/spin", "idem-rou-1",
                spinBody(sessionId, version, straight(7, "1.00"), outside("RED", "2.00")));

        assertThat(spin.get("roundId").asText()).startsWith("rou-");
        assertThat(spin.get("totalBet").decimalValue()).isEqualByComparingTo(new BigDecimal("3.00"));
        int winning = spin.get("winningNumber").asInt();
        assertThat(winning).isBetween(0, 36);
        assertThat(spin.get("winningColor").asText()).isIn("RED", "BLACK", "GREEN");
        assertThat(spin.get("winningBets")).hasSize(2);
        // round persisted + wallet BET row written
        assertThat(roundRepository.findByRoundId(spin.get("roundId").asText())).isPresent();
        assertThat(walletTransactionRepository.findAll())
                .anyMatch(t -> t.getTransactionId().endsWith(":bet"));
    }

    @Test
    void spinIsIdempotentOnRetryWithSameKey() throws Exception {
        JsonNode init = initSession();
        String sessionId = init.get("sessionId").asText();
        long version = init.get("sessionVersion").asLong();
        String body = spinBody(sessionId, version, straight(7, "1.00"));

        MvcResult first = postRaw("/api/v1/roulette/spin", "idem-rou-x", body);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);

        MvcResult replay = postRaw("/api/v1/roulette/spin", "idem-rou-x", body);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getHeader(IdempotencyAspect.HEADER_REPLAY)).isEqualTo("true");
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(roundRepository.count()).isEqualTo(1);
    }

    @Test
    void rejectsUnknownBetType() throws Exception {
        JsonNode init = initSession();
        MvcResult res = postRaw("/api/v1/roulette/spin", "idem-bad-type",
                spinBody(init.get("sessionId").asText(), init.get("sessionVersion").asLong(),
                        outside("NOT_A_BET", "1.00")));
        assertThat(res.getResponse().getStatus()).isEqualTo(400);
        assertThat(res.getResponse().getContentAsString()).contains("VALIDATION_ERROR");
        assertThat(roundRepository.count()).isZero();
    }

    @Test
    void rejectsOffGridChipAmount() throws Exception {
        JsonNode init = initSession();
        MvcResult res = postRaw("/api/v1/roulette/spin", "idem-offgrid",
                spinBody(init.get("sessionId").asText(), init.get("sessionVersion").asLong(),
                        outside("RED", "0.37")));
        assertThat(res.getResponse().getStatus()).isEqualTo(400);
        assertThat(res.getResponse().getContentAsString()).contains("VALIDATION_ERROR");
    }

    @Test
    void rejectsStraightWithOutOfRangeNumber() throws Exception {
        JsonNode init = initSession();
        MvcResult res = postRaw("/api/v1/roulette/spin", "idem-badnum",
                spinBody(init.get("sessionId").asText(), init.get("sessionVersion").asLong(),
                        straight(99, "1.00")));
        assertThat(res.getResponse().getStatus()).isEqualTo(400);
        assertThat(res.getResponse().getContentAsString()).contains("VALIDATION_ERROR");
    }

    @Test
    void rejectsTotalStakeOverTableLimit() throws Exception {
        JsonNode init = initSession();
        // Five 500.00 chips = 2500 > the 2000 table limit (each within the 500 per-spot limit).
        MvcResult res = postRaw("/api/v1/roulette/spin", "idem-overlimit",
                spinBody(init.get("sessionId").asText(), init.get("sessionVersion").asLong(),
                        outside("RED", "500.00"), outside("BLACK", "500.00"), outside("EVEN", "500.00"),
                        outside("ODD", "500.00"), outside("LOW", "500.00")));
        assertThat(res.getResponse().getStatus()).isEqualTo(400);
        assertThat(res.getResponse().getContentAsString()).contains("VALIDATION_ERROR");
        assertThat(roundRepository.count()).isZero();
    }

    @Test
    void rejectsEmptyBets() throws Exception {
        JsonNode init = initSession();
        ObjectNode body = baseBody(init.get("sessionId").asText(), init.get("sessionVersion").asLong());
        body.putArray("bets");
        MvcResult res = postRaw("/api/v1/roulette/spin", "idem-empty", body.toString());
        assertThat(res.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void insufficientBalanceIsRejectedAndPersistsNoRound() throws Exception {
        JsonNode init = initSession();
        String sessionId = init.get("sessionId").asText();
        long version = init.get("sessionVersion").asLong();
        // Drain the balance via the admin endpoint, then attempt a 500 chip.
        postJsonAdmin("/api/v1/admin/wallet/balance",
                mapper.createObjectNode().put("playerId", PLAYER).put("currency", CURRENCY)
                        .put("balance", "1.00").toString());

        MvcResult res = postRaw("/api/v1/roulette/spin", "idem-broke",
                spinBody(sessionId, version, outside("RED", "500.00")));
        assertThat(res.getResponse().getStatus()).isGreaterThanOrEqualTo(400);
        assertThat(roundRepository.count()).isZero();
    }

    @Test
    void roundAppearsInUnifiedHistory() throws Exception {
        JsonNode init = initSession();
        JsonNode spin = postJson("/api/v1/roulette/spin", "idem-hist",
                spinBody(init.get("sessionId").asText(), init.get("sessionVersion").asLong(),
                        straight(7, "1.00")));
        String roundId = spin.get("roundId").asText();

        MvcResult res = mockMvc.perform(get("/api/v1/admin/rounds/" + PLAYER)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + JwtTestFactory.adminToken(PLAYER))).andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        JsonNode rounds = mapper.readTree(res.getResponse().getContentAsString());
        assertThat(rounds).anyMatch(r -> roundId.equals(r.get("roundId").asText())
                && GAME_ID.equals(r.get("gameId").asText())
                && "ROULETTE".equals(r.get("stateContext").asText()));
    }

    // ===================================================== helpers

    private JsonNode initSession() throws Exception {
        return postJson("/api/v1/roulette/init", null,
                mapper.createObjectNode().put("gameId", GAME_ID).put("currency", CURRENCY).toString());
    }

    private ObjectNode baseBody(String sessionId, long version) {
        return mapper.createObjectNode()
                .put("gameId", GAME_ID)
                .put("sessionId", sessionId)
                .put("sessionVersion", version);
    }

    private String spinBody(String sessionId, long version, ObjectNode... bets) {
        ObjectNode body = baseBody(sessionId, version);
        ArrayNode arr = body.putArray("bets");
        for (ObjectNode b : bets) {
            arr.add(b);
        }
        return body.toString();
    }

    private ObjectNode straight(int number, String amount) {
        return mapper.createObjectNode().put("type", "STRAIGHT").put("number", number)
                .put("amount", new BigDecimal(amount));
    }

    private ObjectNode outside(String type, String amount) {
        return mapper.createObjectNode().put("type", type).put("amount", new BigDecimal(amount));
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
