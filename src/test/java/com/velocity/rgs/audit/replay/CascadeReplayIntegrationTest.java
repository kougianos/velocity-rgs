package com.velocity.rgs.audit.replay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocity.rgs.common.idempotency.IdempotencyAspect;
import com.velocity.rgs.common.idempotency.IdempotencyRecordRepository;
import com.velocity.rgs.slot.domain.GameRound;
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

/**
 * <b>The acceptance criterion for cascading reels (§1.2): a cascade round replays bit-exact from its
 * persisted draws.</b>
 *
 * <p>This is the test the whole replay infrastructure exists for. A conventional spin's replay only
 * proves the reel stops were captured; a cascade round's replay proves every refill draw was captured
 * too, in the order the engine consumed them, and that the persisted {@code matrix} /
 * {@code stop_positions} columns really do carry the whole sequence rather than a single grid. Break
 * any one of those - draw a refill from a fresh RNG, persist only the settled board, reorder the
 * refill loop - and this fails.
 *
 * <p>Unlike the pure-math {@code CascadeEngineTest}, this goes through the live HTTP path and Postgres,
 * so it also covers the JSONB round-trip.
 */
@RgsIntegrationTest
@AutoConfigureMockMvc
class CascadeReplayIntegrationTest {

    private static final String PLAYER = "p-cascade-replay";
    private static final String CURRENCY = "EUR";
    /** The cascading game; the other slots settle in one drop and cannot exercise this. */
    private static final String GAME_ID = "gilded-cascade";

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
    void aCascadingRoundReplaysBitExactFromItsPersistedDraws() throws Exception {
        MutableSession session = openSession();

        // ~74% of this game's rounds tumble, so a handful of spins is plenty. Spin until one does,
        // then replay that round specifically - a round that settled in one drop would pass the
        // replay trivially and prove nothing about cascades.
        String cascadeRoundId = null;
        int steps = 0;
        for (int attempt = 0; attempt < 40 && cascadeRoundId == null; attempt++) {
            JsonNode spin = spin(session, "idem-cascade-" + attempt);
            session.version = spin.get("sessionVersion").asLong();
            JsonNode cascadeSteps = spin.get("cascadeSteps");
            if (cascadeSteps != null && cascadeSteps.size() > 1) {
                cascadeRoundId = spin.get("roundId").asText();
                steps = cascadeSteps.size();
            }
            // A free-spins award parks the session outside BASE_GAME; a fresh session is the
            // simplest way back to spinning.
            if (!"BASE_GAME".equals(spin.get("sessionState").get("currentState").asText())) {
                session = openSession();
            }
        }
        assertThat(cascadeRoundId)
                .as("expected at least one tumbling round in 40 spins of %s", GAME_ID)
                .isNotNull();

        // The round persisted the whole sequence, not just the settled board.
        GameRound round = roundRepository.findByRoundId(cascadeRoundId).orElseThrow();
        JsonNode persistedMatrix = mapper.readTree(round.getMatrix());
        JsonNode persistedStops = mapper.readTree(round.getStopPositions());
        assertThat(persistedMatrix.size())
                .as("game_round.matrix holds one grid per drop")
                .isEqualTo(steps);
        assertThat(persistedMatrix.get(0).get(0).isArray())
                .as("a multi-drop round is persisted as a sequence of grids (3 levels of nesting)")
                .isTrue();
        assertThat(persistedStops.size())
                .as("game_round.stop_positions holds one draw set per drop")
                .isEqualTo(steps);

        // And it reconstructs exactly.
        JsonNode replay = replay(cascadeRoundId);
        assertThat(replay.get("matrixMatches").asBoolean())
                .as("every drop's grid and draws must reconstruct from the persisted RNG log")
                .isTrue();
        assertThat(replay.get("totalWinMatches").asBoolean()).isTrue();
        assertThat(replay.get("reconstructedSequence").size())
                .as("the replay tumbled the same number of times as the original")
                .isEqualTo(steps);
    }

    /** A game that does not cascade still writes - and replays - the flat single-grid shape. */
    @Test
    void aNonCascadingRoundKeepsTheFlatShapeAndStillReplays() throws Exception {
        MutableSession session = openSession("aztec-fire");
        JsonNode spin = spin(session, "idem-flat-shape");
        String roundId = spin.get("roundId").asText();

        assertThat(spin.get("cascadeSteps"))
                .as("a conventional spin omits the cascade sequence entirely")
                .isNull();

        GameRound round = roundRepository.findByRoundId(roundId).orElseThrow();
        JsonNode matrix = mapper.readTree(round.getMatrix());
        assertThat(matrix.get(0).get(0).isInt())
                .as("one drop is still persisted as a bare grid (2 levels of nesting)")
                .isTrue();
        assertThat(mapper.readTree(round.getStopPositions()).get(0).isInt())
                .as("and its stops as a bare array of reel positions")
                .isTrue();

        assertThat(replay(roundId).get("matrixMatches").asBoolean()).isTrue();
    }

    /**
     * A Hold &amp; Spin respin is a round too, and it has to replay - not 500.
     *
     * <p>It cannot be reconstructed the way a spin is: a respin re-draws only the unlocked cells, so
     * its recorded draws have neither the count nor the strip bounds of a full reel spin. Replaying one
     * down the spin path fails on the first draw. What makes it work is {@code feature_context}, which
     * records the coins held going in; this asserts the round carries it and reconstructs from it.
     */
    @Test
    void aHoldSpinRespinRoundReplaysFromItsRecordedFeatureContext() throws Exception {
        MutableSession session = openSession("dragon-hoard");

        // Buying the feature is the deterministic way in - the organic trigger is ~1 spin in 580.
        String buyBody = mapper.createObjectNode()
                .put("gameId", "dragon-hoard")
                .put("sessionId", session.id)
                .put("sessionVersion", session.version)
                .put("buyType", "HOLD_SPIN_BUY")
                .put("betSize", "1.00")
                .toString();
        JsonNode buy = postJson("/api/v1/slot/feature/buy", "idem-holdspin-buy", buyBody);
        assertThat(buy.get("enteredState").asText()).isEqualTo("RESPIN_AWAITING");
        session.version = buy.get("sessionVersion").asLong();

        JsonNode start = postJson("/api/v1/slot/feature/start", "idem-holdspin-start",
                mapper.createObjectNode()
                        .put("gameId", "dragon-hoard")
                        .put("sessionId", session.id)
                        .put("sessionVersion", session.version)
                        .put("featureType", "RESPIN")
                        .toString());
        session.version = start.get("sessionVersion").asLong();

        session.gameId = "dragon-hoard";
        JsonNode respin = spin(session, "idem-holdspin-respin");
        String roundId = respin.get("roundId").asText();

        GameRound round = roundRepository.findByRoundId(roundId).orElseThrow();
        assertThat(round.getRoundKind().name())
                .as("a respin is recorded as its own kind of round")
                .isEqualTo("RESPIN");
        assertThat(round.getFeatureContext())
                .as("with the coins held going in, or it cannot be reconstructed")
                .isNotBlank();

        JsonNode replay = replay(roundId);
        assertThat(replay.get("matrixMatches").asBoolean())
                .as("the respin board must reconstruct from its held coins plus its recorded draws")
                .isTrue();
    }

    // ---------------------------------------------------------------- helpers

    /** A live demo session, mutable because {@code sessionVersion} advances with every spin. */
    private static final class MutableSession {
        String id;
        String gameId;
        long version;
    }

    private MutableSession openSession() throws Exception {
        return openSession(GAME_ID);
    }

    private MutableSession openSession(String gameId) throws Exception {
        JsonNode init = postJson("/api/v1/slot/init", null,
                mapper.createObjectNode().put("gameId", gameId).put("currency", CURRENCY).toString());
        MutableSession session = new MutableSession();
        session.id = init.get("sessionId").asText();
        session.gameId = gameId;
        session.version = init.get("sessionVersion").asLong();
        return session;
    }

    private JsonNode spin(MutableSession session, String idemKey) throws Exception {
        String body = mapper.createObjectNode()
                .put("gameId", session.gameId)
                .put("sessionId", session.id)
                .put("sessionVersion", session.version)
                .put("betSize", "1.00")
                .put("powerBetActive", false)
                .toString();
        return postJson("/api/v1/slot/spin", idemKey, body);
    }

    private JsonNode replay(String roundId) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/admin/replay/" + roundId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + JwtTestFactory.adminToken("p-admin"))
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn();
        assertThat(res.getResponse().getStatus())
                .as("replay of %s: %s", roundId, res.getResponse().getContentAsString())
                .isEqualTo(200);
        return mapper.readTree(res.getResponse().getContentAsString());
    }

    private JsonNode postJson(String url, String idemKey, String body) throws Exception {
        var req = post(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + playerJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (idemKey != null) {
            req.header(IdempotencyAspect.HEADER_KEY, idemKey);
        }
        MvcResult res = mockMvc.perform(req).andReturn();
        assertThat(res.getResponse().getStatus())
                .as("%s -> %s", url, res.getResponse().getContentAsString())
                .isEqualTo(200);
        return mapper.readTree(res.getResponse().getContentAsString());
    }

    private String playerJwt() {
        return JwtTestFactory.validToken(PLAYER, "ses-" + UUID.randomUUID(), CURRENCY);
    }
}
