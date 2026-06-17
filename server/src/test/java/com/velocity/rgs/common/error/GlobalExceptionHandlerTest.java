package com.velocity.rgs.common.error;

import com.velocity.rgs.testsupport.JwtTestFactory;
import com.velocity.rgs.testsupport.RgsIntegrationTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@RgsIntegrationTest
@AutoConfigureMockMvc
class GlobalExceptionHandlerTest {

    @Autowired private MockMvc mockMvc;

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, mode = EnumSource.Mode.EXCLUDE,
            names = {"INTERNAL_ERROR"})
    void allMappedErrorCodesReturnDeclaredHttpStatus(ErrorCode code) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/test/errors/throw?code=" + code.name())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + JwtTestFactory.validToken("p-1", "s-1", "EUR")))
                .andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(code.httpStatus().value());
        assertThat(res.getResponse().getContentAsString()).contains(code.name());
    }

    @Test
    void optimisticLockMapsToSessionVersionConflict() throws Exception {
        MvcResult res = mockMvc.perform(post("/api/test/errors/optimistic")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + JwtTestFactory.validToken("p-1", "s-1", "EUR")))
                .andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(409);
        assertThat(res.getResponse().getContentAsString()).contains("SESSION_VERSION_CONFLICT");
    }

    @Test
    void unhandledExceptionMapsToInternalError() throws Exception {
        MvcResult res = mockMvc.perform(post("/api/test/errors/unhandled")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + JwtTestFactory.validToken("p-1", "s-1", "EUR")))
                .andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(500);
        assertThat(res.getResponse().getContentAsString()).contains("INTERNAL_ERROR");
    }

    @Test
    void beanValidationFailureMapsToValidationErrorWithDetails() throws Exception {
        MvcResult res = mockMvc.perform(post("/api/test/errors/validate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + JwtTestFactory.validToken("p-1", "s-1", "EUR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"amount\":0}"))
                .andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(400);
        String body = res.getResponse().getContentAsString();
        assertThat(body).contains("VALIDATION_ERROR");
        assertThat(body).contains("details");
    }
}
