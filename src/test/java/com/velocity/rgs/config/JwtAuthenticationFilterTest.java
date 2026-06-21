package com.velocity.rgs.config;

import com.velocity.rgs.common.idempotency.IdempotencyAspect;
import com.velocity.rgs.testsupport.JwtTestFactory;
import com.velocity.rgs.testsupport.RgsIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@RgsIntegrationTest
@AutoConfigureMockMvc
class JwtAuthenticationFilterTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void publicActuatorHealthIsOpen() throws Exception {
        MvcResult res = mockMvc.perform(get("/actuator/health")).andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void missingTokenReturns401() throws Exception {
        MvcResult res = mockMvc.perform(post("/api/test/idempotency")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(401);
        assertThat(res.getResponse().getContentAsString()).contains("AUTH_FAILED");
    }

    @Test
    void malformedTokenReturns401() throws Exception {
        MvcResult res = mockMvc.perform(post("/api/test/idempotency")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt")
                        .header(IdempotencyAspect.HEADER_KEY, "k")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void expiredTokenReturns401() throws Exception {
        MvcResult res = mockMvc.perform(post("/api/test/idempotency")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + JwtTestFactory.expiredToken("p-1"))
                        .header(IdempotencyAspect.HEADER_KEY, "k")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void badIssuerReturns401() throws Exception {
        MvcResult res = mockMvc.perform(post("/api/test/idempotency")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + JwtTestFactory.tokenWithBadIssuer("p-1"))
                        .header(IdempotencyAspect.HEADER_KEY, "k")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(401);
    }
}
