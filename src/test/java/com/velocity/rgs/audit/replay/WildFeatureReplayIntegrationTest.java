package com.velocity.rgs.audit.replay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocity.rgs.common.error.RgsException;
import com.velocity.rgs.common.idempotency.IdempotencyAspect;
import com.velocity.rgs.common.idempotency.IdempotencyRecordRepository;
import com.velocity.rgs.session.domain.GameState;
import com.velocity.rgs.session.persistence.GameSessionRepository;
import com.velocity.rgs.session.service.SessionStore;
import com.velocity.rgs.slot.domain.GameRound;
import com.velocity.rgs.slot.math.config.SlotMathDefinition;
import com.velocity.rgs.slot.math.config.SlotMathRegistry;
import com.velocity.rgs.slot.math.domain.ReelStripSet;
import com.velocity.rgs.slot.math.engine.WildFeatureEngine;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
 *   <li><b>dragon-hoard</b> has sticky and walking wilds, whose board depends on the spin before it.
 *       It replays because the round records the carry it span with in {@code feature_context}; strip
 *       that column and it is refused with a reason rather than reported as a mismatch, which is what
 *       every round played before the capture existed still does.</li>
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
    @Autowired private SlotMathRegistry mathRegistry;

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
     * The ticket this block exists for. Sticky/walking wilds are seeded from the carry held between
     * spins, so the persisted board is not a function of the round's own draws - until the round records
     * that carry alongside them, which it now does. Every free spin reconstructs, and every one of them
     * has the context that made it possible.
     */
    @Test
    void freeSpinRoundsOnAWalkingWildGameReconstruct() {
        List<GameRound> freeSpins = playUntilFreeSpins("dragon-hoard");
        assertThat(freeSpins)
                .as("no free-spin round was produced; the test cannot assert what it exists to assert")
                .isNotEmpty();

        for (GameRound round : freeSpins) {
            assertThat(round.getFeatureContext())
                    .as("free-spin round %s recorded no wild carry", round.getRoundId())
                    .isNotNull();
            RoundReplayResult result = replayService.replay(round.getRoundId());
            assertThat(result.matrixMatches())
                    .as("free-spin round %s did not reconstruct", round.getRoundId()).isTrue();
            assertThat(result.totalWinMatches()).isTrue();
            assertThat(result.carriedWildMode()).isEqualTo("WALKING");
        }
    }

    /**
     * That the carry is <em>read</em> and not merely stored: rewrite it and the reconstruction must stop
     * agreeing with the persisted board.
     *
     * <p>Without this, every assertion above would still pass if {@code ReplayService} ignored
     * {@code feature_context} entirely and the rounds it happened to see all carried nothing - which is
     * most of them, since a wild has to be drawn before one can stick. The injected cell is chosen off
     * the reconstructed board so it lands somewhere that is provably not a wild already, making the
     * divergence certain rather than probable.
     */
    @Test
    void tamperingWithTheRecordedCarryBreaksTheReconstruction() {
        List<GameRound> freeSpins = playUntilFreeSpins("dragon-hoard");
        assertThat(freeSpins).isNotEmpty();

        // Deliberately not the settling round: overwriting its context would strip the free-spins
        // running totals too, and it would then refuse for that reason instead of failing to
        // reconstruct - which is a different claim than the one under test here.
        GameRound round = midFeatureSpin(freeSpins);
        SlotMathDefinition math = mathRegistry.require(round.getGameId(), round.getMathVersion());
        int[][] board = replayService.replay(round.getRoundId()).reconstructedMatrix();

        // A walking wild steps one reel left, so a carry at (r, c+1) lands on (r, c).
        int[] target = firstNonWildWithAReelToItsRight(board, math);
        assertThat(target).as("every cell on the board is already a wild; nothing to tamper with")
                .isNotNull();

        round.setFeatureContext(String.format(
                "{\"stickyWilds\":[{\"row\":%d,\"col\":%d,\"remainingSpins\":2}]}",
                target[0], target[1] + 1));
        roundRepository.save(round);

        assertThatThrownBy(() -> replayService.replay(round.getRoundId()))
                .isInstanceOf(RgsException.class)
                .hasMessageContaining("mismatch");
    }

    /**
     * A round played before the capture existed has no carry to read back, and is refused with a reason
     * rather than replayed as though it had carried nothing - which for any spin after the first it did
     * not. 409, not 500: nothing is broken, the input was simply never written down. The stored rounds
     * are aged by clearing the column, which is exactly the state they are in on disk.
     */
    @Test
    void aRoundPredatingTheCaptureIsRefusedWithAReason() throws Exception {
        String roundId = ageIntoThePreCaptureEra(playUntilFreeSpins("dragon-hoard"));

        MvcResult res = mockMvc.perform(post("/api/v1/admin/replay/" + roundId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + JwtTestFactory.adminToken("p-admin"))
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn();

        assertThat(res.getResponse().getStatus()).isEqualTo(409);
        String body = res.getResponse().getContentAsString();
        assertThat(body).contains("ROUND_NOT_REPLAYABLE");
        assertThat(body).contains("predates");
        assertThat(body).as("refused for the wild carry, not for something else").contains("wilds");
    }

    /** And no link is minted for a round that cannot be proved - a share must never outrun the proof. */
    @Test
    void noProofLinkIsMintedForARoundThatCannotBeReconstructed() throws Exception {
        String roundId = ageIntoThePreCaptureEra(playUntilFreeSpins("dragon-hoard"));

        MvcResult res = mockMvc.perform(post("/api/v1/admin/replay/" + roundId + "/share")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + JwtTestFactory.adminToken("p-admin"))
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn();

        assertThat(res.getResponse().getStatus()).isEqualTo(409);
        assertThat(res.getResponse().getContentAsString()).contains("ROUND_NOT_REPLAYABLE");
    }

    /** And a round that does reconstruct mints one, which is the whole change these tests are about. */
    @Test
    void aProofLinkIsMintedForAWalkingWildRound() throws Exception {
        List<GameRound> freeSpins = playUntilFreeSpins("dragon-hoard");
        assertThat(freeSpins).isNotEmpty();

        MvcResult res = mockMvc.perform(post(
                        "/api/v1/admin/replay/" + midFeatureSpin(freeSpins).getRoundId() + "/share")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + JwtTestFactory.adminToken("p-admin"))
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn();

        assertThat(res.getResponse().getStatus())
                .as("%s", res.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(mapper.readTree(res.getResponse().getContentAsString()).get("url").asText())
                .isNotBlank();
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

    /**
     * The round that settles a free-spins feature pays the <em>whole feature</em>, not its own board:
     * every earlier spin's win, times the bonus-buy boost. Replaying only its lines compared two
     * different quantities and reported a divergence on a round where nothing was wrong.
     *
     * <p>Covered on both wild games because the defect was never about wilds - it was reachable on
     * gilded-cascade the whole time, and only invisible on dragon-hoard because its free spins were
     * refused for an unrelated reason. Buying the feature is what makes the assertion sharp: the boost
     * is greater than 1, so a replay ignoring it cannot coincidentally agree.
     */
    @Test
    void theRoundThatSettlesAFreeSpinsFeatureReconstructsItsWholePayout() {
        for (String gameId : List.of("gilded-cascade", "dragon-hoard")) {
            clean();
            GameRound settling = playUntilFreeSpins(gameId).stream()
                    .filter(WildFeatureReplayIntegrationTest::settlesTheFeature)
                    .findFirst()
                    .orElse(null);
            assertThat(settling).as("%s never settled its feature", gameId).isNotNull();
            assertThat(settling.getTotalWin().signum())
                    .as("%s settled for nothing, so the assertion below proves nothing", gameId)
                    .isPositive();

            RoundReplayResult result = replayService.replay(settling.getRoundId());

            assertThat(result.matrixMatches()).as("%s settling board", gameId).isTrue();
            assertThat(result.totalWinMatches()).as("%s settling payout", gameId).isTrue();
            assertThat(result.reconstructedTotalWin())
                    .isEqualByComparingTo(settling.getTotalWin());
            assertThat(result.featureBuyMultiplier())
                    .as("%s bought its feature, so the boost belongs on the round", gameId)
                    .isGreaterThan(BigDecimal.ONE);
        }
    }

    /** And a settling round recorded before those totals were captured is refused, not judged. */
    @Test
    void aSettlingRoundPredatingTheCaptureIsRefusedRatherThanReportedAsDiverged() {
        GameRound settling = playUntilFreeSpins("gilded-cascade").stream()
                .filter(WildFeatureReplayIntegrationTest::settlesTheFeature)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the feature never settled"));
        settling.setFeatureContext(null);
        roundRepository.save(settling);

        assertThatThrownBy(() -> replayService.replay(settling.getRoundId()))
                .isInstanceOf(RgsException.class)
                .hasMessageContaining("free-spins feature")
                .hasMessageContaining("predates");
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Whether a round ended the free-spins feature: a free spin (no debit - the trigger paid) whose
     * after-state is the base game.
     */
    private static boolean settlesTheFeature(GameRound round) {
        return round.getBetTransactionId() == null
                && round.getStateContext() == GameState.BASE_GAME;
    }

    /**
     * Strips the wild carry off a round, leaving it in the state every round written before the capture
     * existed is in on disk: replayable-looking, with the one input it needs missing.
     */
    private String ageIntoThePreCaptureEra(List<GameRound> freeSpins) {
        GameRound round = midFeatureSpin(freeSpins);
        round.setFeatureContext(null);
        return roundRepository.save(round).getRoundId();
    }

    /**
     * A free spin from the middle of the feature - one that neither started nor settled it, so its only
     * un-drawn input is the wild carry. The settling round has a second one (the running totals it pays
     * out) and would refuse for that reason first, masking whatever a wild-carry test meant to assert.
     */
    private static GameRound midFeatureSpin(List<GameRound> freeSpins) {
        GameRound round = freeSpins.stream()
                .filter(r -> !settlesTheFeature(r))
                .findFirst()
                .orElse(null);
        assertThat(round).as("the feature produced no non-settling free spin").isNotNull();
        return round;
    }

    /** {@code [row, col]} of a cell holding something other than the wild, with a reel to its right. */
    private static int[] firstNonWildWithAReelToItsRight(int[][] board, SlotMathDefinition math) {
        for (int r = 0; r < board.length; r++) {
            for (int c = 0; c < board[r].length - 1; c++) {
                if (!WildFeatureEngine.isWild(math, board[r][c])) {
                    return new int[] {r, c};
                }
            }
        }
        return null;
    }

    /**
     * Buys into free spins and plays them out, returning the free-spin rounds produced.
     *
     * <p>Bought rather than waited for: the organic scatter trigger is rare enough that spinning for it
     * would make the test slow and flaky, and what is under test is the replay of a free-spin round, not
     * how one is reached.
     *
     * <p>The loop runs until the feature is over - the session is back in the base game - rather than a
     * fixed count, because a retrigger adds five more spins and a fixed bound would quietly return a
     * half-played feature with no settling round in it. It still stops early if SPIN becomes illegal,
     * since free spins can drop into an awaiting state, and the cap is a backstop against a retrigger
     * chain rather than the normal exit.
     *
     * <p>Returns every free-spin round of the feature, identified the way {@code ReplayService}
     * identifies one: no bet debit, because the trigger already paid. Filtering on
     * {@code FREE_SPINS_LOOP} instead - the round's <em>after</em> state - silently drops the spin that
     * settles the feature, which is the one round whose payout is not its own board, and which is
     * therefore the one most worth replaying.
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

            for (int i = 0; i < 80; i++) {
                JsonNode spin = postJson("/api/v1/slot/spin", "fs-" + run + "-" + i,
                        mapper.createObjectNode()
                                .put("gameId", gameId)
                                .put("sessionId", sessionId)
                                .put("sessionVersion", version)
                                .put("betSize", "1.00")
                                .put("powerBetActive", false)
                                .toString());
                version = spin.get("sessionVersion").asLong();
                if (featureIsOver(spin) || !canSpin(spin)) {
                    break;
                }
            }
            return roundRepository.findByPlayerIdOrderByCreatedAtDesc(PLAYER).stream()
                    .filter(r -> r.getBetTransactionId() == null)
                    .toList();
        } catch (Exception ex) {
            throw new IllegalStateException("could not drive " + gameId + " into free spins", ex);
        }
    }

    /** The feature has paid out and handed the session back to the base game. */
    private static boolean featureIsOver(JsonNode response) {
        JsonNode state = response.path("sessionState").path("currentState");
        return "BASE_GAME".equals(state.asText());
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
