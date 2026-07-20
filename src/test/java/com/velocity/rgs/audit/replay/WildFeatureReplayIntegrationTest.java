package com.velocity.rgs.audit.replay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocity.rgs.common.idempotency.IdempotencyAspect;
import com.velocity.rgs.common.idempotency.IdempotencyRecordRepository;
import com.velocity.rgs.session.persistence.GameSessionRepository;
import com.velocity.rgs.session.service.SessionStore;
import com.velocity.rgs.slot.domain.GameRound;
import com.velocity.rgs.slot.math.domain.ReelStripSet;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Replay against the wild-feature games (§1.4), which the original replay cover missed entirely - it
 * exercised base-game and cascade rounds only, so nothing noticed that a free-spin round on a game with
 * wilds could not be reconstructed at all.
 *
 * <p>The two behaviours differ and the distinction is the point:
 * <ul>
 *   <li><b>gilded-cascade</b> expands wilds, which is a pure function of the drawn grid - it replays,
 *       and must, because {@code SlotEngineService} persists the <em>expanded</em> board;</li>
 *   <li><b>dragon-hoard</b> has sticky and walking wilds seeded from session state no round records -
 *       it is refused with a reason rather than reported as a mismatch.</li>
 * </ul>
 */
@RgsIntegrationTest
@AutoConfigureMockMvc
class WildFeatureReplayIntegrationTest {

    private static final String PLAYER = "p-wild-replay";
    private static final String CURRENCY = "EUR";

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
    @Autowired private ReplayService replayService;

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

    /**
     * The regression this whole class exists for. Expanding wilds rewrite the board before evaluation
     * and it is the rewritten board that is stored, so a replay that skipped the transform compared the
     * drawn grid against a board that was never only drawn - and answered 500.
     */
    @Test
    void freeSpinRoundsOnAnExpandingWildGameReconstruct() {
        List<GameRound> freeSpins = playUntilFreeSpins("gilded-cascade");
        assertThat(freeSpins)
                .as("no free-spin round was produced; the test cannot assert what it exists to assert")
                .isNotEmpty();

        for (GameRound round : freeSpins) {
            RoundReplayResult result = replayService.replay(round.getRoundId());
            assertThat(result.matrixMatches())
                    .as("free-spin round %s did not reconstruct", round.getRoundId()).isTrue();
            assertThat(result.totalWinMatches()).isTrue();
            assertThat(result.reelStripSet()).isEqualTo(ReelStripSet.FREE_SPINS.name());
        }
    }

    /**
     * Sticky/walking wilds are seeded from {@code active_feature_payload}, which is session state, not
     * round state - so the board is not derivable from this round's draws at any price. Refused with a
     * reason, and refused as 409 rather than 500, because nothing is broken: the input was never
     * captured.
     */
    @Test
    void freeSpinRoundsOnAStickyWildGameAreRefusedWithAReason() throws Exception {
        List<GameRound> freeSpins = playUntilFreeSpins("dragon-hoard");
        assertThat(freeSpins).isNotEmpty();

        MvcResult res = mockMvc.perform(post("/api/v1/admin/replay/" + freeSpins.get(0).getRoundId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + JwtTestFactory.adminToken("p-admin"))
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn();

        assertThat(res.getResponse().getStatus()).isEqualTo(409);
        String body = res.getResponse().getContentAsString();
        assertThat(body).contains("ROUND_NOT_REPLAYABLE");
        assertThat(body).contains("wilds");
    }

    /** And no link is minted for a round that cannot be proved - a share must never outrun the proof. */
    @Test
    void noProofLinkIsMintedForARoundThatCannotBeReconstructed() throws Exception {
        List<GameRound> freeSpins = playUntilFreeSpins("dragon-hoard");
        assertThat(freeSpins).isNotEmpty();

        MvcResult res = mockMvc.perform(post(
                        "/api/v1/admin/replay/" + freeSpins.get(0).getRoundId() + "/share")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + JwtTestFactory.adminToken("p-admin"))
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn();

        assertThat(res.getResponse().getStatus()).isEqualTo(409);
        assertThat(res.getResponse().getContentAsString()).contains("ROUND_NOT_REPLAYABLE");
    }

    /** Base-game rounds are unaffected either way: wilds on both games are scoped to FREE_SPINS. */
    @Test
    void baseGameRoundsOnWildGamesStillReconstruct() throws Exception {
        for (String gameId : List.of("gilded-cascade", "dragon-hoard")) {
            clean();
            String roundId = spinOnce(gameId);
            RoundReplayResult result = replayService.replay(roundId);
            assertThat(result.matrixMatches()).as("base-game round on %s", gameId).isTrue();
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Buys into free spins and plays them out, returning the free-spin rounds produced.
     *
     * <p>Bought rather than waited for: the organic scatter trigger is rare enough that spinning for it
     * would make the test slow and flaky, and what is under test is the replay of a free-spin round, not
     * how one is reached.
     *
     * <p>The spin loop is driven by {@code availableActions} rather than a fixed count. Free spins on
     * these games can drop into RESPIN_AWAITING or PICK_COLLECT_AWAITING, where SPIN is not legal - the
     * loop stops there instead of asserting its way into a 409, because the rounds banked up to that
     * point are all the test needs.
     */
    private List<GameRound> playUntilFreeSpins(String gameId) {
        try {
            String run = UUID.randomUUID().toString();
            JsonNode init = postJson("/api/v1/slot/init", null,
                    mapper.createObjectNode().put("gameId", gameId).put("currency", CURRENCY).toString());
            String sessionId = init.get("sessionId").asText();
            long version = init.get("sessionVersion").asLong();

            JsonNode buy = postJson("/api/v1/slot/feature/buy", "buy-" + run,
                    mapper.createObjectNode()
                            .put("gameId", gameId)
                            .put("sessionId", sessionId)
                            .put("sessionVersion", version)
                            .put("buyType", "FREE_SPINS_BUY")
                            .put("betSize", "1.00")
                            .toString());
            assertThat(buy.get("enteredState").asText()).isEqualTo("FREE_SPINS_AWAITING");

            JsonNode start = postJson("/api/v1/slot/feature/start", "start-" + run,
                    mapper.createObjectNode()
                            .put("gameId", gameId)
                            .put("sessionId", sessionId)
                            .put("sessionVersion", buy.get("sessionVersion").asLong())
                            .put("featureType", "FREE_SPINS")
                            .toString());
            version = start.get("sessionVersion").asLong();

            for (int i = 0; i < 20; i++) {
                JsonNode spin = postJson("/api/v1/slot/spin", "fs-" + run + "-" + i,
                        mapper.createObjectNode()
                                .put("gameId", gameId)
                                .put("sessionId", sessionId)
                                .put("sessionVersion", version)
                                .put("betSize", "1.00")
                                .put("powerBetActive", false)
                                .toString());
                version = spin.get("sessionVersion").asLong();
                if (!canSpin(spin)) {
                    break;
                }
            }
            return roundRepository.findByPlayerIdOrderByCreatedAtDesc(PLAYER).stream()
                    .filter(r -> r.getStateContext() != null
                            && r.getStateContext().name().equals("FREE_SPINS_LOOP"))
                    .toList();
        } catch (Exception ex) {
            throw new IllegalStateException("could not drive " + gameId + " into free spins", ex);
        }
    }

    private static boolean canSpin(JsonNode response) {
        JsonNode actions = response.get("availableActions");
        if (actions == null) {
            return false;
        }
        for (JsonNode action : actions) {
            if ("SPIN".equals(action.asText())) {
                return true;
            }
        }
        return false;
    }

    private String spinOnce(String gameId) throws Exception {
        JsonNode init = postJson("/api/v1/slot/init", null,
                mapper.createObjectNode().put("gameId", gameId).put("currency", CURRENCY).toString());
        return postJson("/api/v1/slot/spin", "base-" + UUID.randomUUID(),
                mapper.createObjectNode()
                        .put("gameId", gameId)
                        .put("sessionId", init.get("sessionId").asText())
                        .put("sessionVersion", init.get("sessionVersion").asLong())
                        .put("betSize", "1.00")
                        .put("powerBetActive", false)
                        .toString()).get("roundId").asText();
    }

    private JsonNode postJson(String url, String idemKey, String body) throws Exception {
        var req = post(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer "
                        + JwtTestFactory.validToken(PLAYER, "ses-" + UUID.randomUUID(), CURRENCY))
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
}
