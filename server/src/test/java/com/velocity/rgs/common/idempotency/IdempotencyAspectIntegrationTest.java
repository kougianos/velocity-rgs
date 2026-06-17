package com.velocity.rgs.common.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocity.rgs.testsupport.JwtTestFactory;
import com.velocity.rgs.testsupport.RgsIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@RgsIntegrationTest
@AutoConfigureMockMvc
class IdempotencyAspectIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TestIdempotentController controller;
    @Autowired private IdempotencyRecordRepository repository;

    @BeforeEach
    void clean() {
        controller.reset();
        repository.deleteAll();
    }

    @Test
    void firstCallExecutesAndStoresRecord() throws Exception {
        MvcResult res = call("key-1", "{\"message\":\"hello\"}").andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        assertThat(res.getResponse().getHeader(IdempotencyAspect.HEADER_REPLAY)).isEqualTo("false");
        assertThat(controller.invocationCount()).isEqualTo(1);
        assertThat(repository.findByScopeAndKey("test:echo:p-1", "key-1")).isPresent();
    }

    @Test
    void replayReturnsCachedResponseWithoutReExecuting() throws Exception {
        String first = call("key-2", "{\"message\":\"hi\"}")
                .andReturn().getResponse().getContentAsString();
        MvcResult replay = call("key-2", "{\"message\":\"hi\"}").andReturn();

        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getHeader(IdempotencyAspect.HEADER_REPLAY)).isEqualTo("true");
        assertThat(replay.getResponse().getContentAsString()).isEqualTo(first);
        assertThat(controller.invocationCount()).isEqualTo(1);
    }

    @Test
    void differentPayloadSameKeyRaisesConflict() throws Exception {
        call("key-3", "{\"message\":\"one\"}").andReturn();
        MvcResult conflict = call("key-3", "{\"message\":\"two\"}").andReturn();

        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
        assertThat(conflict.getResponse().getContentAsString()).contains("IDEMPOTENCY_KEY_CONFLICT");
        assertThat(controller.invocationCount()).isEqualTo(1);
    }

    @Test
    void missingHeaderRaisesValidationError() throws Exception {
        MvcResult res = mockMvc.perform(post("/api/test/idempotency")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + JwtTestFactory.validToken("p-1", "s-1", "EUR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"oops\"}"))
                .andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(400);
        assertThat(res.getResponse().getContentAsString()).contains("VALIDATION_ERROR");
        assertThat(controller.invocationCount()).isZero();
    }

    private org.springframework.test.web.servlet.ResultActions call(String key, String body) throws Exception {
        return mockMvc.perform(post("/api/test/idempotency")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + JwtTestFactory.validToken("p-1", "s-1", "EUR"))
                .header(IdempotencyAspect.HEADER_KEY, key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}
