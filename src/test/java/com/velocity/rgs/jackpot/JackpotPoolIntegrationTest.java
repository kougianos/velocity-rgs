package com.velocity.rgs.jackpot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocity.rgs.testsupport.RgsIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The progressive pools as the lobby reads them (§2).
 *
 * <p>Reads the pools the migration seeds rather than writing its own, because the seeding is part of
 * what is being tested: a fresh database has to come up with four tiers already on it, or the lobby's
 * first load has nothing to draw.
 */
@RgsIntegrationTest
@AutoConfigureMockMvc
class JackpotPoolIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;

    /**
     * Anonymous, like the catalog. The lobby draws the strip before anyone signs in, so a token
     * requirement here would mean the figures only appeared to logged-in players - which is the
     * opposite of what a jackpot is advertised for.
     */
    @Test
    void poolsAreReadableWithoutAToken() throws Exception {
        MvcResult res = mockMvc.perform(get("/api/v1/jackpots")).andReturn();

        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        assertThat(read(res)).hasSize(4);
    }

    /**
     * Ladder order, smallest first, and not by amount.
     *
     * <p>Ordering by value would let the strip reorder itself the moment a lower tier was contributed
     * past a higher one that had just been won, which is a display that shuffles while a player is
     * looking at it.
     */
    @Test
    void tiersComeBackInLadderOrder() throws Exception {
        List<String> tiers = new ArrayList<>();
        for (JsonNode pool : read(mockMvc.perform(get("/api/v1/jackpots")).andReturn())) {
            tiers.add(pool.get("tier").asText());
        }
        assertThat(tiers).containsExactly("MINI", "MINOR", "MAJOR", "MEGA");
    }

    /** Every figure is a real row, seeded at a floor it can never fall below. */
    @Test
    void everyPoolCarriesItsSeedAndSitsAtOrAboveIt() throws Exception {
        for (JsonNode pool : read(mockMvc.perform(get("/api/v1/jackpots")).andReturn())) {
            assertThat(pool.get("seed").decimalValue().signum()).isPositive();
            assertThat(pool.get("amount").decimalValue())
                    .isGreaterThanOrEqualTo(pool.get("seed").decimalValue());
            assertThat(pool.get("grownBy").decimalValue())
                    .isEqualByComparingTo(pool.get("amount").decimalValue()
                            .subtract(pool.get("seed").decimalValue()));
        }
    }

    /**
     * Money leaves at the currency's own scale, not the column's.
     *
     * <p>{@code NUMERIC(19,4)} reads back as 10.0000, and a client that printed what it was given would
     * show a jackpot with four decimal places.
     */
    @Test
    void amountsAreScaledToTheCurrency() throws Exception {
        String json = mockMvc.perform(get("/api/v1/jackpots")).andReturn()
                .getResponse().getContentAsString();

        // Asserted against the raw response rather than a parsed tree, deliberately. Jackson normalises
        // BigDecimal on the way in - 10.00 is read back as 1E+1 - so a parsed assertion would be
        // measuring this test's own parser and would pass whatever the server had actually sent.
        List<String> money = new ArrayList<>();
        Matcher matcher = Pattern.compile("\"(?:amount|seed|grownBy)\":([^,}]+)").matcher(json);
        while (matcher.find()) {
            money.add(matcher.group(1));
        }

        assertThat(money).hasSize(12);
        assertThat(money).allMatch(v -> v.matches("-?\\d+\\.\\d{2}"));
    }

    /**
     * A currency with no pools is an empty list, not an error. "What jackpots do you run in USD" has a
     * truthful answer when the answer is none, and it is not a 404.
     */
    @Test
    void aCurrencyWithNoPoolsIsEmptyRatherThanAnError() throws Exception {
        MvcResult res = mockMvc.perform(get("/api/v1/jackpots").param("currency", "USD")).andReturn();

        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        assertThat(read(res)).isEmpty();
    }

    private List<JsonNode> read(MvcResult res) throws Exception {
        JsonNode body = mapper.readTree(res.getResponse().getContentAsString());
        List<JsonNode> pools = new ArrayList<>();
        body.forEach(pools::add);
        return pools;
    }
}
