package com.velocity.rgs.qa.dev;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocity.rgs.testsupport.RgsIntegrationTest;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@RgsIntegrationTest
@AutoConfigureMockMvc
class DevTokenControllerIntegrationTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-32b";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;

    @Test
    @SuppressWarnings("unchecked")
    void mintsValidSelfSignedJwtWithoutPriorAuth() throws Exception {
        String body = mapper.createObjectNode()
                .put("playerId", "p-dev-1")
                .put("sessionId", "s-dev-1")
                .put("currency", "EUR")
                .putPOJO("roles", List.of("PLAYER"))
                .put("ttlMinutes", 30)
                .toString();

        MvcResult res = mockMvc.perform(post("/api/v1/dev/token")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();

        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        JsonNode response = mapper.readTree(res.getResponse().getContentAsString());
        String token = response.get("token").asText();
        assertThat(token).isNotBlank();
        assertThat(response.get("expiresAt").asText()).isNotBlank();

        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Claims claims = Jwts.parser().verifyWith(key).requireIssuer("velocity-rgs").build()
                .parseSignedClaims(token).getPayload();
        assertThat(claims.getSubject()).isEqualTo("p-dev-1");
        assertThat(claims.get("sid", String.class)).isEqualTo("s-dev-1");
        assertThat(claims.get("cur", String.class)).isEqualTo("EUR");
        assertThat((List<Object>) claims.get("roles", List.class)).containsExactly("PLAYER");
    }

    @Test
    @SuppressWarnings("unchecked")
    void mintsAdminTokenWhenRolesContainsAdmin() throws Exception {
        String body = mapper.createObjectNode()
                .put("playerId", "p-admin-1").put("sessionId", "s-admin-1").put("currency", "EUR")
                .putPOJO("roles", List.of("ADMIN", "PLAYER")).toString();

        MvcResult res = mockMvc.perform(post("/api/v1/dev/token")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();

        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        String token = mapper.readTree(res.getResponse().getContentAsString()).get("token").asText();
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        assertThat((List<Object>) claims.get("roles", List.class)).contains("ADMIN");
    }

    @Test
    void rejectsBlankPlayerIdAs400() throws Exception {
        String body = mapper.createObjectNode()
                .put("playerId", "").put("sessionId", "s-1").put("currency", "EUR").toString();

        MvcResult res = mockMvc.perform(post("/api/v1/dev/token")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(400);
    }
}
