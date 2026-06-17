package com.velocity.rgs.observability;

import com.velocity.rgs.testsupport.RgsIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@RgsIntegrationTest
@AutoConfigureMockMvc
class MdcCorrelationFilterTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void generatesTraceIdWhenAbsent() throws Exception {
        MvcResult result = mockMvc.perform(get("/actuator/health")).andReturn();
        assertThat(result.getResponse().getHeader(MdcCorrelationFilter.TRACE_HEADER)).isNotBlank();
    }

    @Test
    void echoesProvidedTraceId() throws Exception {
        String trace = "trace-from-client-123";
        MvcResult result = mockMvc.perform(get("/actuator/health")
                .header(MdcCorrelationFilter.TRACE_HEADER, trace)).andReturn();
        assertThat(result.getResponse().getHeader(MdcCorrelationFilter.TRACE_HEADER)).isEqualTo(trace);
    }
}
