package com.velocity.rgs.jackpot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocity.rgs.common.idempotency.IdempotencyAspect;
import com.velocity.rgs.jackpot.domain.JackpotContribution;
import com.velocity.rgs.jackpot.domain.JackpotPool;
import com.velocity.rgs.jackpot.domain.JackpotPoolId;
import com.velocity.rgs.jackpot.domain.JackpotTier;
import com.velocity.rgs.jackpot.persistence.JackpotPoolRepository;
import com.velocity.rgs.slot.math.config.ProgressiveJackpotConfig;
import com.velocity.rgs.testsupport.JwtTestFactory;
import com.velocity.rgs.testsupport.RgsIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Stakes feeding the shared pools, and the transaction that binds the two (§2).
 *
 * <p>Every assertion is a <em>delta</em> rather than an absolute. The pools are global state shared by
 * every test that spins in this JVM, so pinning an absolute figure would make this class fail whenever
 * an unrelated test happened to run first - which is a fact about the suite, not about the feature.
 */
@RgsIntegrationTest
@AutoConfigureMockMvc
class JackpotContributionIntegrationTest {

    private static final String GAME = "aztec-fire";
    private static final String CURRENCY = "EUR";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JackpotPoolRepository poolRepository;
    @Autowired private JackpotService jackpotService;
    @Autowired private JackpotProperties properties;
    @Autowired private TransactionTemplate transactionTemplate;

    /**
     * The headline: a real spin moves all four pools, each by its configured share.
     *
     * <p>Asserted against the pool rows rather than the API, because a 1.00 stake at 1% puts 0.004 into
     * the Mini - real money in a {@code NUMERIC(19,4)} column, and invisible at a currency's two
     * decimals. Reading the rows is what shows the arithmetic is happening at all.
     */
    @Test
    void aSpinFeedsEveryTierByItsConfiguredShare() throws Exception {
        Map<JackpotTier, BigDecimal> before = amounts();

        String player = player();
        String sessionId = init(player);
        MvcResult spin = spin(player, sessionId, "1.00");
        assertThat(spin.getResponse().getStatus()).isEqualTo(200);

        Map<JackpotTier, BigDecimal> after = amounts();
        BigDecimal stake = new BigDecimal("1.00");
        BigDecimal rate = properties.getDefaultContributionRate();

        for (JackpotTier tier : JackpotTier.values()) {
            BigDecimal expected = stake.multiply(rate)
                    .multiply(properties.tier(tier).getShare())
                    .setScale(4, java.math.RoundingMode.DOWN);
            assertThat(after.get(tier).subtract(before.get(tier)))
                    .as("%s grew by rate x stake x share", tier)
                    .isEqualByComparingTo(expected);
        }
    }

    /** The response tells the player what their spin fed, and it is the sum of what the pools took. */
    @Test
    void theSpinResponseReportsWhatItActuallyContributed() throws Exception {
        Map<JackpotTier, BigDecimal> before = amounts();

        String player = player();
        String sessionId = init(player);
        JsonNode body = mapper.readTree(
                spin(player, sessionId, "1.00").getResponse().getContentAsString());

        JsonNode jackpot = body.get("jackpot");
        assertThat(jackpot).isNotNull();
        assertThat(jackpot.get("currency").asText()).isEqualTo(CURRENCY);
        assertThat(jackpot.get("pools")).hasSize(4);

        Map<JackpotTier, BigDecimal> after = amounts();
        BigDecimal actuallyMoved = BigDecimal.ZERO;
        for (JackpotTier tier : JackpotTier.values()) {
            actuallyMoved = actuallyMoved.add(after.get(tier).subtract(before.get(tier)));
        }
        assertThat(jackpot.get("amount").decimalValue())
                .as("the figure shown is the figure the pools received")
                .isEqualByComparingTo(actuallyMoved);
    }

    /**
     * The atomicity claim, which is the whole reason the contribution sits inside the spin's
     * transaction: a failure anywhere after it leaves the pools exactly as they were.
     *
     * <p>Driven through {@code TransactionTemplate} rather than by breaking a spin, because the
     * property under test is the transaction boundary itself. A test that engineered a spin failure
     * would prove the same thing while also depending on <em>where</em> in the spin the failure landed.
     */
    @Test
    void aRolledBackTransactionLeavesThePoolsUntouched() {
        Map<JackpotTier, BigDecimal> before = amounts();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            JackpotContribution contribution = jackpotService.contribute(
                    new ProgressiveJackpotConfig(true, new BigDecimal("0.5000")),
                    CURRENCY, new BigDecimal("100.00"));
            // Contributed inside the transaction, and a large amount, so a leak would be unmissable.
            assertThat(contribution.amount()).isEqualByComparingTo("50.0000");
            throw new IllegalStateException("spin blew up after the pool was fed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(amounts())
                .as("a contribution that did not commit is a contribution that never happened")
                .isEqualTo(before);
    }

    /** A game with no block feeds nothing, which is also exactly when it advertises no jackpot. */
    @Test
    void aGameThatDoesNotConfigureTheBlockContributesNothing() {
        Map<JackpotTier, BigDecimal> before = amounts();

        JackpotContribution contribution = jackpotService.contribute(
                ProgressiveJackpotConfig.disabled(), CURRENCY, new BigDecimal("100.00"));

        assertThat(contribution.isPresent()).isFalse();
        assertThat(contribution.amount()).isEqualByComparingTo("0");
        assertThat(amounts()).isEqualTo(before);
    }

    /**
     * A free spin costs nothing and therefore feeds nothing.
     *
     * <p>The contribution follows the money actually debited, not the bet the round is evaluated at. A
     * player cannot grow the jackpot with a stake they were never charged.
     */
    @Test
    void aZeroStakeContributesNothing() {
        Map<JackpotTier, BigDecimal> before = amounts();

        JackpotContribution contribution = jackpotService.contribute(
                new ProgressiveJackpotConfig(true, null), CURRENCY, BigDecimal.ZERO);

        assertThat(contribution.isPresent()).isFalse();
        assertThat(amounts()).isEqualTo(before);
    }

    // ---------------------------------------------------------------- helpers

    private Map<JackpotTier, BigDecimal> amounts() {
        Map<JackpotTier, BigDecimal> byTier = new EnumMap<>(JackpotTier.class);
        for (JackpotTier tier : JackpotTier.values()) {
            byTier.put(tier, poolRepository.findById(new JackpotPoolId(tier, CURRENCY))
                    .map(JackpotPool::getAmount)
                    .orElseThrow()
                    .stripTrailingZeros());
        }
        return byTier;
    }

    private String player() {
        return "p-jp-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String init(String player) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/slot/init")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(player))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.createObjectNode()
                                .put("gameId", GAME).put("currency", CURRENCY).toString()))
                .andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        return mapper.readTree(res.getResponse().getContentAsString()).get("sessionId").asText();
    }

    private MvcResult spin(String player, String sessionId, String bet) throws Exception {
        long version = mapper.readTree(mockMvc.perform(post("/api/v1/slot/init")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(player))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.createObjectNode()
                                .put("gameId", GAME).put("currency", CURRENCY).toString()))
                .andReturn().getResponse().getContentAsString()).get("sessionVersion").asLong();

        return mockMvc.perform(post("/api/v1/slot/spin")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(player))
                        .header(IdempotencyAspect.HEADER_KEY, "jp-" + UUID.randomUUID())
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

    private String token(String player) {
        return JwtTestFactory.validToken(player, "ses-" + UUID.randomUUID(), CURRENCY);
    }
}
