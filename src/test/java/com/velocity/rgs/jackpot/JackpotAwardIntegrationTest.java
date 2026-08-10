package com.velocity.rgs.jackpot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocity.rgs.common.idempotency.IdempotencyAspect;
import com.velocity.rgs.jackpot.domain.JackpotPool;
import com.velocity.rgs.jackpot.domain.JackpotPoolId;
import com.velocity.rgs.jackpot.domain.JackpotTier;
import com.velocity.rgs.jackpot.domain.JackpotWin;
import com.velocity.rgs.jackpot.persistence.JackpotPoolRepository;
import com.velocity.rgs.jackpot.persistence.JackpotWinRepository;
import com.velocity.rgs.testsupport.JwtTestFactory;
import com.velocity.rgs.testsupport.RgsIntegrationTest;
import com.velocity.rgs.wallet.domain.WalletTransactionType;
import com.velocity.rgs.wallet.persistence.WalletTransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Paying a progressive out, exactly once (§2).
 *
 * <p>Exactly-once is the property this feature lives or dies on: a jackpot awarded twice is the one
 * mistake in the system that cannot be shrugged off. It is defended in three places and each is
 * asserted separately below, because a test that only proved "the total came out right" would pass
 * just as well with two of the three broken.
 */
@RgsIntegrationTest
@AutoConfigureMockMvc
class JackpotAwardIntegrationTest {

    private static final String GAME = "aztec-fire";
    private static final String CURRENCY = "EUR";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JackpotService jackpotService;
    @Autowired private JackpotPoolRepository poolRepository;
    @Autowired private JackpotWinRepository winRepository;
    @Autowired private JackpotProperties properties;
    @Autowired private WalletTransactionRepository walletTransactionRepository;
    @Autowired private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    /**
     * The headline: a won pool pays the player, resets to its seed, and leaves one audit row.
     *
     * <p>The reset matters as much as the payout. A pool that paid out without resetting would pay the
     * same prize to the next winner too.
     */
    @Test
    void winningAPoolPaysItOutAndResetsItToSeed() throws Exception {
        String player = player();
        String sessionId = init(player);
        // Grow the Mini above its seed first, so "reset to seed" is a visible drop rather than a no-op.
        spin(player, sessionId, "5.00");

        BigDecimal poolBefore = amount(JackpotTier.MINI);
        BigDecimal seed = seed(JackpotTier.MINI);
        assertThat(poolBefore).isGreaterThan(seed);

        jackpotService.forceNextWin(player, JackpotTier.MINI);
        JsonNode body = mapper.readTree(spin(player, sessionId, "5.00").getResponse().getContentAsString());

        JsonNode win = body.get("jackpotWin");
        assertThat(win).as("the spin reports the jackpot it just won").isNotNull();
        assertThat(win.get("tier").asText()).isEqualTo("MINI");
        assertThat(win.get("amount").decimalValue()).isGreaterThan(BigDecimal.ZERO);

        // The pool is back at its floor, plus at most the sub-cent remainder the payout could not
        // cover. Deliberately NOT asserted as "less than it was before": a spin contributes before it
        // awards, so the pool ends at seed + carried, and carried can land on exactly the figure the
        // pool started at. That is not a bug and a test that forbade it would be wrong, not unlucky.
        BigDecimal oneMinorUnit = new BigDecimal("0.01");
        assertThat(amount(JackpotTier.MINI))
                .isGreaterThanOrEqualTo(seed)
                .isLessThan(seed.add(oneMinorUnit));
        assertThat(win.get("amount").decimalValue())
                .as("the prize was the pool as it stood, which was well above the floor")
                .isGreaterThan(poolBefore);

        List<JackpotWin> wins = winRepository.findByPlayerIdOrderByWonAtDesc(player);
        assertThat(wins).hasSize(1);
        assertThat(wins.get(0).getTier()).isEqualTo(JackpotTier.MINI);
        assertThat(wins.get(0).getSeedAmount()).isEqualByComparingTo(seed);
    }

    /** The payout reaches the wallet, as its own transaction type rather than an ordinary win. */
    @Test
    void theAwardIsCreditedAsAJackpotTransaction() throws Exception {
        String player = player();
        String sessionId = init(player);
        jackpotService.forceNextWin(player, JackpotTier.MINOR);
        spin(player, sessionId, "1.00");

        JackpotWin win = winRepository.findByPlayerIdOrderByWonAtDesc(player).get(0);
        assertThat(walletTransactionRepository.findAll())
                .filteredOn(t -> t.getTransactionId().equals(win.getTxId()))
                .singleElement()
                .satisfies(t -> {
                    assertThat(t.getType()).isEqualTo(WalletTransactionType.JACKPOT_WIN);
                    assertThat(t.getAmount()).isEqualByComparingTo(win.getAmount());
                });
    }

    /**
     * Defence one: a retried spin replays its stored response and never re-runs the award.
     *
     * <p>This is the case a real client actually produces - a timeout and a retry - and the one where
     * paying twice would be easiest.
     */
    @Test
    void replayingTheWinningSpinPaysOnlyOnce() throws Exception {
        String player = player();
        String sessionId = init(player);
        jackpotService.forceNextWin(player, JackpotTier.MINI);

        String key = "jp-award-" + UUID.randomUUID();
        String body = spinBody(sessionId, "1.00", version(player));
        MvcResult first = spinWithKey(player, key, body);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(mapper.readTree(first.getResponse().getContentAsString()).get("jackpotWin")).isNotNull();

        BigDecimal poolAfterWin = amount(JackpotTier.MINI);

        MvcResult replay = spinWithKey(player, key, body);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getHeader(IdempotencyAspect.HEADER_REPLAY)).isEqualTo("true");
        assertThat(replay.getResponse().getContentAsString())
                .as("a replay is the same payload, byte for byte")
                .isEqualTo(first.getResponse().getContentAsString());

        assertThat(winRepository.findByPlayerIdOrderByWonAtDesc(player))
                .as("one win row, not two").hasSize(1);
        assertThat(amount(JackpotTier.MINI))
                .as("the pool did not move again").isEqualByComparingTo(poolAfterWin);
    }

    /**
     * Defence two: two spins racing for the same pool, only one wins it.
     *
     * <p>Simulated by handing the second caller a stale version, which is exactly what it would hold if
     * the first had committed between its read and its write. The loser awards nothing rather than
     * paying out a figure that no longer exists.
     */
    @Test
    void twoSpinsRacingForOnePoolCannotBothWinIt() {
        JackpotPool pool = poolRepository.findById(new JackpotPoolId(JackpotTier.MAJOR, CURRENCY))
                .orElseThrow();
        long staleVersion = pool.getVersion();
        BigDecimal seed = pool.getSeedAmount();

        // Each attempt in its own transaction, which is what two concurrent spins would be. Both hold
        // the same version because both read before either wrote.
        int firstWinner = transactionTemplate.execute(status -> poolRepository.resetIfUnchanged(
                JackpotTier.MAJOR, CURRENCY, seed, staleVersion, Instant.now()));
        int secondWinner = transactionTemplate.execute(status -> poolRepository.resetIfUnchanged(
                JackpotTier.MAJOR, CURRENCY, seed, staleVersion, Instant.now()));

        assertThat(firstWinner).as("the first caller takes the pool").isEqualTo(1);
        assertThat(secondWinner).as("the second finds the version moved and takes nothing").isZero();
    }

    /**
     * Defence three: the database refuses a second award for the same round even if the code asked.
     *
     * <p>The two defences above are application logic and could be refactored wrong. This one cannot,
     * and it is the one an auditor would trust.
     */
    @Test
    void twoWinsForOneRoundAreRejectedByTheDatabase() throws Exception {
        String player = player();
        String sessionId = init(player);
        jackpotService.forceNextWin(player, JackpotTier.MINI);
        spin(player, sessionId, "1.00");

        JackpotWin existing = winRepository.findByPlayerIdOrderByWonAtDesc(player).get(0);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                winRepository.saveAndFlush(JackpotWin.builder()
                        .winId(UUID.randomUUID().toString())
                        .roundId(existing.getRoundId())
                        .playerId(player)
                        .sessionId(existing.getSessionId())
                        .gameId(GAME)
                        .tier(JackpotTier.MEGA)
                        .currency(CURRENCY)
                        .amount(new BigDecimal("99999.0000"))
                        .seedAmount(new BigDecimal("10000.0000"))
                        .txId("a-different-tx-" + UUID.randomUUID())
                        .wonAt(Instant.now())
                        .build()))
                .as("uq_jackpot_win_round is what makes a double award impossible rather than unlikely")
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    /**
     * The pools on a winning response are the pools <em>after</em> the award.
     *
     * <p>The contribution snapshots them before the award runs, so without a refresh the client would
     * be handed a jackpot celebration and a pool that had visibly not reset - and would render the two
     * side by side.
     */
    @Test
    void aWinningResponseReportsThePoolAlreadyReset() throws Exception {
        String player = player();
        String sessionId = init(player);
        spin(player, sessionId, "5.00");

        BigDecimal before = amount(JackpotTier.MINI);
        jackpotService.forceNextWin(player, JackpotTier.MINI);
        JsonNode body = mapper.readTree(spin(player, sessionId, "5.00").getResponse().getContentAsString());

        JsonNode mini = null;
        for (JsonNode pool : body.get("jackpot").get("pools")) {
            if ("MINI".equals(pool.get("tier").asText())) {
                mini = pool;
            }
        }
        assertThat(mini).isNotNull();
        assertThat(mini.get("amount").decimalValue())
                .as("the response shows the reset pool, not the one that was just paid out")
                .isLessThan(before);
    }

    /**
     * An ordinary spin reports no win, so the client tests for a jackpot by the field's presence.
     *
     * <p>Deterministic because the suite's award odds are unreachable (see application-test.yml). With
     * the shipped odds live this assertion would be a 1-in-80 coin flip, which is how it first failed
     * on CI rather than locally.
     */
    @Test
    void anOrdinarySpinCarriesNoAward() throws Exception {
        String player = player();
        String sessionId = init(player);
        JsonNode body = mapper.readTree(spin(player, sessionId, "0.20").getResponse().getContentAsString());

        assertThat(body.has("jackpotWin")).isFalse();
        assertThat(body.get("jackpot")).as("it still contributed, though").isNotNull();
    }

    /**
     * Guards the arrangement the rest of this class depends on.
     *
     * <p>Two things at once. The odds must be unreachable, or any test in the suite that spins can win
     * a jackpot, credit a wallet and break an assertion in a test measuring something else - which is
     * exactly how {@code anOrdinarySpinCarriesNoAward} first failed on CI and not locally.
     *
     * <p>And the seeds and shares must have survived: the test profile overrides only
     * {@code award-one-in-n} per tier, relying on Spring's map binding to merge that leaf over the
     * shipped values rather than replacing the tier wholesale. If that ever stopped merging, the
     * remaining values would be null and this says so directly instead of leaving a confusing
     * startup failure.
     */
    @Test
    void theSuiteCannotAwardAJackpotByChance() {
        for (JackpotTier tier : JackpotTier.values()) {
            assertThat(properties.tier(tier).getAwardOneInN())
                    .as("%s odds must be unreachable in the test profile", tier)
                    .isGreaterThan(1_000_000);
            assertThat(properties.tier(tier).getSeed())
                    .as("%s seed survived the profile override", tier).isNotNull();
            assertThat(properties.tier(tier).getShare())
                    .as("%s share survived the profile override", tier).isNotNull();
        }
    }

    // ---------------------------------------------------------------- helpers

    private BigDecimal amount(JackpotTier tier) {
        return poolRepository.findById(new JackpotPoolId(tier, CURRENCY)).orElseThrow().getAmount();
    }

    private BigDecimal seed(JackpotTier tier) {
        return poolRepository.findById(new JackpotPoolId(tier, CURRENCY)).orElseThrow().getSeedAmount();
    }

    private String player() {
        return "p-award-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String init(String player) throws Exception {
        return mapper.readTree(initRaw(player)).get("sessionId").asText();
    }

    private long version(String player) throws Exception {
        return mapper.readTree(initRaw(player)).get("sessionVersion").asLong();
    }

    private String initRaw(String player) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/slot/init")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(player))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.createObjectNode()
                                .put("gameId", GAME).put("currency", CURRENCY).toString()))
                .andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        return res.getResponse().getContentAsString();
    }

    private String spinBody(String sessionId, String bet, long version) {
        return mapper.createObjectNode()
                .put("gameId", GAME)
                .put("sessionId", sessionId)
                .put("sessionVersion", version)
                .put("betSize", bet)
                .put("powerBetActive", false)
                .toString();
    }

    private MvcResult spin(String player, String sessionId, String bet) throws Exception {
        return spinWithKey(player, "jp-" + UUID.randomUUID(), spinBody(sessionId, bet, version(player)));
    }

    private MvcResult spinWithKey(String player, String key, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/slot/spin")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(player))
                        .header(IdempotencyAspect.HEADER_KEY, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private String token(String player) {
        return JwtTestFactory.validToken(player, "ses-" + UUID.randomUUID(), CURRENCY);
    }
}
