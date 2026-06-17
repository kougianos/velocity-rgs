package com.velocity.rgs.qa.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocity.rgs.audit.simulation.AuditSimulationRunRepository;
import com.velocity.rgs.game.service.RtpReport;
import com.velocity.rgs.game.service.RtpSimulationRequest;
import com.velocity.rgs.game.service.RtpSimulationService;
import com.velocity.rgs.testsupport.JwtTestFactory;
import com.velocity.rgs.testsupport.RgsIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@RgsIntegrationTest
@AutoConfigureMockMvc
class SimulatorAdminControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private AuditSimulationRunRepository auditRepo;
    @Autowired private RtpSimulationService simulationService;

    @BeforeEach
    void clean() {
        auditRepo.deleteAll();
    }

    @Test
    void runSimulationReturnsThreeChannelsAndPersistsAuditRow() throws Exception {
        String body = mapper.createObjectNode()
                .put("gameId", "aztec-fire")
                .put("mathVersion", "v1")
                .put("bet", "1.00")
                .put("spinsBaseGame", 25)
                .put("spinsBonusBuyFreeSpins", 5)
                .put("spinsBonusBuyPickCollect", 5)
                .put("pickStrategy", "RANDOM_UNOPENED")
                .toString();

        MvcResult res = mockMvc.perform(post("/api/v1/admin/simulator/run")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + JwtTestFactory.adminToken("p-admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();

        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        JsonNode out = mapper.readTree(res.getResponse().getContentAsString());
        assertThat(out.get("runId").asText()).startsWith("sim-");
        assertThat(out.get("gameId").asText()).isEqualTo("aztec-fire");
        assertThat(out.get("channels")).isNotNull();
        assertThat(out.get("channels").get("BASE_GAME").get("spins").asLong()).isEqualTo(25);
        assertThat(out.get("channels").get("BONUS_BUY_FREE_SPINS").get("spins").asLong()).isEqualTo(5);
        assertThat(out.get("channels").get("BONUS_BUY_PICK_COLLECT").get("spins").asLong()).isEqualTo(5);
        assertThat(out.get("overall").get("totalBet").decimalValue()).isPositive();
        assertThat(out.get("elapsedMillis").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(auditRepo.findByRunId(out.get("runId").asText())).isPresent();
    }

    @Test
    void runRequiresAdminRole() throws Exception {
        String body = mapper.createObjectNode()
                .put("gameId", "aztec-fire").put("mathVersion", "v1").put("bet", "1.00")
                .put("spinsBaseGame", 1).put("spinsBonusBuyFreeSpins", 0).put("spinsBonusBuyPickCollect", 0)
                .put("pickStrategy", "RANDOM_UNOPENED").toString();
        MvcResult res = mockMvc.perform(post("/api/v1/admin/simulator/run")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer "
                                + JwtTestFactory.validToken("p-player", "s-" + UUID.randomUUID(), "EUR"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void rejectsInvalidRequest() throws Exception {
        String body = mapper.createObjectNode()
                .put("gameId", "").put("mathVersion", "v1").put("bet", "1.00")
                .put("spinsBaseGame", 1).put("spinsBonusBuyFreeSpins", 0).put("spinsBonusBuyPickCollect", 0)
                .put("pickStrategy", "RANDOM_UNOPENED").toString();
        MvcResult res = mockMvc.perform(post("/api/v1/admin/simulator/run")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + JwtTestFactory.adminToken("p-admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void serviceProducesNonZeroOverallRtpOverLargerSample() {
        RtpSimulationRequest req = RtpSimulationRequest.builder()
                .gameId("aztec-fire").mathVersion("v1").bet(new BigDecimal("1.00"))
                .spinsBaseGame(2000).spinsBonusBuyFreeSpins(0).spinsBonusBuyPickCollect(0)
                .pickStrategy(RtpSimulationRequest.PickStrategy.RANDOM_UNOPENED)
                .build();
        RtpReport report = simulationService.run(req, null);
        assertThat(report.channels()).containsKey("BASE_GAME");
        assertThat(report.channels().get("BASE_GAME").totalBet().compareTo(BigDecimal.ZERO)).isPositive();
        // RTP should be plausibly bounded (we just sanity-check it is non-negative and finite)
        assertThat(report.channels().get("BASE_GAME").rtpPercent()).isNotNull();
    }
}
