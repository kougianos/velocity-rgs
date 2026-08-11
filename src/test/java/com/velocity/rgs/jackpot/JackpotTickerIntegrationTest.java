package com.velocity.rgs.jackpot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocity.rgs.common.idempotency.IdempotencyAspect;
import com.velocity.rgs.jackpot.domain.JackpotPoolView;
import com.velocity.rgs.jackpot.domain.JackpotTier;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * What the lobby ticker reads (§2).
 *
 * <p>Two claims worth guarding. The Redis cache holds a <em>view</em> and never the pool, so an award
 * must be visible immediately rather than after a TTL - a ticker advertising a prize that has already
 * been paid is the one staleness this design cannot tolerate. And the "last won by" line goes on an
 * anonymous public endpoint, so the player id has to leave masked.
 */
@RgsIntegrationTest
@AutoConfigureMockMvc
class JackpotTickerIntegrationTest {

    private static final String GAME = "aztec-fire";
    private static final String CURRENCY = "EUR";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JackpotService jackpotService;
    @Autowired private JackpotPoolCache poolCache;

    /**
     * An award evicts the cached view, so the drop is visible on the very next read.
     *
     * <p>The read before the win also warms the cache, which is what makes this meaningful: without
     * eviction the stale entry would still be serving the pre-win figure here.
     */
    @Test
    void winningAPoolIsVisibleToTheTickerImmediately() throws Exception {
        String player = player();
        String sessionId = init(player);
        // Grown through the service rather than a warm-up spin. A spin can trigger free spins or Pick &
        // Collect and leave the session out of base game, which makes the NEXT spin an illegal
        // transition - a 409 that has nothing to do with jackpots and fails perhaps one run in a
        // hundred. Any test that needs two spins from one player is quietly betting on the reels.
        jackpotService.contribute(new ProgressiveJackpotConfig(true, null),
                CURRENCY, new BigDecimal("50.00"));

        BigDecimal before = amountOf(jackpotService.pools(CURRENCY), JackpotTier.MINI);
        assertThat(poolCache.get(CURRENCY)).as("that read warmed the cache").isNotNull();

        jackpotService.forceNextWin(player, JackpotTier.MINI);
        spin(player, sessionId, "5.00");

        assertThat(amountOf(jackpotService.pools(CURRENCY), JackpotTier.MINI))
                .as("the ticker must not keep advertising a prize that has been paid")
                .isLessThan(before);
    }

    /** The cache is populated by a read and holds the same figures the read returned. */
    @Test
    void theTickerReadIsServedThroughTheCache() {
        poolCache.evict(CURRENCY);
        assertThat(poolCache.get(CURRENCY)).isNull();

        List<JackpotPoolView> fromDb = jackpotService.pools(CURRENCY);
        List<JackpotPoolView> cached = poolCache.get(CURRENCY);

        assertThat(cached).isNotNull().hasSameSizeAs(fromDb);
        assertThat(amountOf(cached, JackpotTier.MEGA))
                .isEqualByComparingTo(amountOf(fromDb, JackpotTier.MEGA));
    }

    /**
     * After a win the tier carries its provenance, with the player id masked.
     *
     * <p>{@code /api/v1/jackpots} is anonymous, so anything on this view is published to anyone who
     * loads the lobby. A ticker needs to say the prize is real and winnable; it does not need to name
     * whoever won it.
     */
    @Test
    void theLastWinIsReportedWithoutNamingThePlayer() throws Exception {
        String player = player();
        String sessionId = init(player);
        jackpotService.forceNextWin(player, JackpotTier.MEGA);
        spin(player, sessionId, "1.00");

        JackpotPoolView mega = jackpotService.pools(CURRENCY).stream()
                .filter(p -> p.tier() == JackpotTier.MEGA).findFirst().orElseThrow();

        assertThat(mega.lastWonAt()).isNotNull();
        assertThat(mega.lastWonAmount()).isGreaterThan(BigDecimal.ZERO);
        assertThat(mega.lastWonBy())
                .as("masked, and specifically not the raw id")
                .isNotEqualTo(player)
                .endsWith("***");
        assertThat(player).startsWith(mega.lastWonBy().replace("***", ""));
    }

    /** Masking keeps a short id from leaking whole, and never returns something for nothing. */
    @Test
    void maskingHandlesShortAndMissingIds() {
        assertThat(JackpotPoolView.maskPlayer(null)).isNull();
        assertThat(JackpotPoolView.maskPlayer("  ")).isNull();
        assertThat(JackpotPoolView.maskPlayer("abc")).isEqualTo("***");
        // Nine characters kept, the rest masked: "demo-a1b2" of "demo-a1b2c3d4".
        assertThat(JackpotPoolView.maskPlayer("demo-a1b2c3d4")).isEqualTo("demo-a1b2***");
    }

    // ---------------------------------------------------------------- helpers

    private static BigDecimal amountOf(List<JackpotPoolView> pools, JackpotTier tier) {
        return pools.stream().filter(p -> p.tier() == tier).findFirst().orElseThrow().amount();
    }

    private String player() {
        return "p-tick-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String init(String player) throws Exception {
        return mapper.readTree(initRaw(player)).get("sessionId").asText();
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

    private void spin(String player, String sessionId, String bet) throws Exception {
        long version = mapper.readTree(initRaw(player)).get("sessionVersion").asLong();
        MvcResult res = mockMvc.perform(post("/api/v1/slot/spin")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(player))
                        .header(IdempotencyAspect.HEADER_KEY, "tick-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.createObjectNode()
                                .put("gameId", GAME)
                                .put("sessionId", sessionId)
                                .put("sessionVersion", version)
                                .put("betSize", bet)
                                .put("powerBetActive", false)
                                .toString()))
                .andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
    }

    private String token(String player) {
        return JwtTestFactory.validToken(player, "ses-" + UUID.randomUUID(), CURRENCY);
    }
}
