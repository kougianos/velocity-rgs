package com.velocity.rgs.rg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocity.rgs.testsupport.RgsIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Self-exclusion severing a session that is already open (§4.2).
 *
 * <p>Everything here turns on one thing: the token being refused is <em>valid</em>. It is correctly
 * signed, issued by the right issuer and nowhere near expiry, so no amount of verification would turn
 * it away. If these tests ever start passing because the token expired, they are testing nothing.
 *
 * <p>Each test mints against its own player id rather than sharing one and cleaning up between, because
 * the denylist lives in a real Redis here - a leaked key from a failed test would otherwise fail the
 * next one for the wrong reason.
 */
@RgsIntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "rgs.rg.enabled=true")
class TokenSeveringIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;

    /**
     * The headline. One token, good for an hour, refused mid-life because the account behind it closed.
     *
     * <p>The status call before self-exclusion is not a formality: it proves the token was accepted
     * moments earlier, so the 403 afterwards can only be the denylist.
     */
    @Test
    void selfExclusionSeversTheTokenThatIsAlreadyOpen() throws Exception {
        String player = player();
        String token = mintToken(player);

        assertThat(status(token).getResponse().getStatus()).isEqualTo(200);

        MvcResult excluded = selfExclude(token);
        assertThat(excluded.getResponse().getStatus()).isEqualTo(200);

        MvcResult after = status(token);
        assertThat(after.getResponse().getStatus()).isEqualTo(403);
        assertThat(after.getResponse().getContentAsString()).contains("RG_SELF_EXCLUDED");
    }

    /**
     * A severed token fails as self-exclusion, not as a bad token.
     *
     * <p>The distinction is the whole reason the filter grew a second error code. A 401 AUTH_FAILED
     * would be indistinguishable from an expired session, and a client that cannot tell those apart
     * does the worst possible thing with it: silently mints a new token and carries on.
     */
    @Test
    void severedTokenIsNotReportedAsAnAuthFailure() throws Exception {
        String player = player();
        String token = mintToken(player);
        selfExclude(token);

        MvcResult after = status(token);
        JsonNode body = mapper.readTree(after.getResponse().getContentAsString());

        assertThat(body.get("code").asText()).isEqualTo("RG_SELF_EXCLUDED");
        assertThat(body.get("httpStatus").asInt()).isEqualTo(403);
        assertThat(after.getResponse().getContentAsString()).doesNotContain("AUTH_FAILED");
    }

    /** Severing is scoped to the player who excluded themselves, and nobody else notices. */
    @Test
    void otherPlayersTokensSurvive() throws Exception {
        String excludedPlayer = player();
        String bystander = player();
        String excludedToken = mintToken(excludedPlayer);
        String bystanderToken = mintToken(bystander);

        selfExclude(excludedToken);

        assertThat(status(excludedToken).getResponse().getStatus()).isEqualTo(403);
        assertThat(status(bystanderToken).getResponse().getStatus()).isEqualTo(200);
    }

    /**
     * Every token the player holds dies, not just the one that made the call.
     *
     * <p>This is the two-tab case the feature exists for: the tab running the game and the tab running
     * the RG panel hold different tokens, and excluding from one has to reach the other.
     */
    @Test
    void everyLiveTokenForThePlayerIsSevered() throws Exception {
        String player = player();
        String gameTab = mintToken(player);
        String panelTab = mintToken(player);

        selfExclude(panelTab);

        assertThat(status(gameTab).getResponse().getStatus()).isEqualTo(403);
        assertThat(status(panelTab).getResponse().getStatus()).isEqualTo(403);
    }

    /**
     * A token minted after self-exclusion is not a way back in.
     *
     * <p>Minting is anonymous in demo mode, so this is trivially reachable and worth pinning down: the
     * fresh token passes the filter, because there is nothing to deny it by, and then reads back a
     * closed account from Postgres. The denylist ends sessions; the database is what refuses play. A
     * regression that inverted those would show up here as a status of {@code selfExcluded: false}.
     */
    @Test
    void aFreshTokenPassesTheFilterAndStillReadsBackAsExcluded() throws Exception {
        String player = player();
        selfExclude(mintToken(player));

        MvcResult fresh = status(mintToken(player));
        assertThat(fresh.getResponse().getStatus()).isEqualTo(200);

        JsonNode body = mapper.readTree(fresh.getResponse().getContentAsString());
        assertThat(body.get("selfExcluded").asBoolean()).isTrue();
        assertThat(body.get("canPlay").asBoolean()).isFalse();
    }

    /**
     * The demo reset brings the severed tokens back with the account.
     *
     * <p>Without this the reset would restore a player whose every open tab was still dead, which looks
     * exactly like the reset silently failing.
     */
    @Test
    void demoResetUnseversTheOriginalToken() throws Exception {
        String player = player();
        String original = mintToken(player);
        selfExclude(original);
        assertThat(status(original).getResponse().getStatus()).isEqualTo(403);

        // The reset needs a token the filter will accept, which the severed one no longer is - the same
        // re-mint the RG panel does for itself.
        mockMvc.perform(post("/api/v1/rg/dev/reset")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + mintToken(player)))
                .andReturn();

        assertThat(status(original).getResponse().getStatus()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- helpers

    /** A player id nothing else in the suite will touch. */
    private String player() {
        return "p-sever-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Mints through the real endpoint, so the jti and its denylist registration are the shipped ones. */
    private String mintToken(String playerId) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/dev/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(java.util.Map.of(
                                "playerId", playerId,
                                "sessionId", UUID.randomUUID().toString(),
                                "currency", "EUR",
                                "roles", java.util.List.of("PLAYER"),
                                "ttlMinutes", 60))))
                .andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        return mapper.readTree(res.getResponse().getContentAsString()).get("token").asText();
    }

    private MvcResult status(String token) throws Exception {
        return mockMvc.perform(get("/api/v1/rg/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn();
    }

    private MvcResult selfExclude(String token) throws Exception {
        return mockMvc.perform(post("/api/v1/rg/self-exclude")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirm\":\"SELF-EXCLUDE\"}"))
                .andReturn();
    }
}
