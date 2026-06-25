package com.velocity.rgs.audit.pickaudit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocity.rgs.common.idempotency.IdempotencyAspect;
import com.velocity.rgs.common.idempotency.IdempotencyRecordRepository;
import com.velocity.rgs.slot.persistence.FeaturePurchaseEventRepository;
import com.velocity.rgs.slot.persistence.GameRoundRepository;
import com.velocity.rgs.slot.persistence.PickCollectSnapshotRepository;
import com.velocity.rgs.session.persistence.GameSessionRepository;
import com.velocity.rgs.session.service.SessionStore;
import com.velocity.rgs.testsupport.JwtTestFactory;
import com.velocity.rgs.testsupport.PickCollectTestSupport;
import com.velocity.rgs.testsupport.RgsIntegrationTest;
import com.velocity.rgs.wallet.persistence.WalletBalanceRepository;
import com.velocity.rgs.wallet.persistence.WalletTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@RgsIntegrationTest
@AutoConfigureMockMvc
@Import(PickAuditEventIntegrationTest.CapturingConfig.class)
class PickAuditEventIntegrationTest {

    private static final String PLAYER = "p-pick-audit";
    private static final String CURRENCY = "EUR";
    private static final String GAME_ID = "aztec-fire";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private GameSessionRepository sessionRepository;
    @Autowired private SessionStore sessionStore;
    @Autowired private GameRoundRepository roundRepository;
    @Autowired private FeaturePurchaseEventRepository featurePurchaseRepository;
    @Autowired private PickCollectSnapshotRepository pickRepository;
    @Autowired private WalletBalanceRepository walletBalanceRepository;
    @Autowired private WalletTransactionRepository walletTransactionRepository;
    @Autowired private IdempotencyRecordRepository idempotencyRepository;
    @Autowired private CapturingPickAuditListener listener;

    @BeforeEach
    void clean() {
        listener.events.clear();
        roundRepository.deleteAll();
        featurePurchaseRepository.deleteAll();
        pickRepository.deleteAll();
        idempotencyRepository.deleteAll();
        sessionRepository.deleteAll();
        walletTransactionRepository.deleteAll();
        walletBalanceRepository.deleteAll();
        sessionStore.evict(PLAYER);
    }

    @Test
    void everyPickEmitsAuditEventWithBeforeAndAfterHashes() throws Exception {
        JsonNode init = postJson("/api/v1/slot/init", null,
                mapper.createObjectNode().put("gameId", GAME_ID).put("currency", CURRENCY).toString());
        String sessionId = init.get("sessionId").asText();

        // Drop into PICK_COLLECT_AWAITING as the organic in-spin trigger would (no longer buyable).
        long version = PickCollectTestSupport.forcePickCollectAwaiting(sessionStore, sessionId);

        // Start feature
        JsonNode start = postJson("/api/v1/slot/feature/start", "idem-pa-start",
                mapper.createObjectNode()
                        .put("gameId", GAME_ID).put("sessionId", sessionId)
                        .put("sessionVersion", version)
                        .put("featureType", "PICK_COLLECT").toString());
        version = start.get("sessionVersion").asLong();
        assertThat(listener.events).isEmpty();

        int boardSize = start.get("activeFeatureView").get("boardSize").asInt();
        int picks = 0;
        for (int i = 0; i < boardSize; i++) {
            JsonNode pick = postJson("/api/v1/slot/feature/pick", "idem-pa-pick-" + i,
                    mapper.createObjectNode()
                            .put("gameId", GAME_ID).put("sessionId", sessionId)
                            .put("sessionVersion", version)
                            .put("position", i).toString());
            picks++;
            version = pick.get("sessionVersion").asLong();
            if (pick.get("featureCompleted").asBoolean()) {
                break;
            }
        }

        assertThat(listener.events).hasSize(picks);
        for (int i = 0; i < listener.events.size(); i++) {
            PickAuditEvent ev = listener.events.get(i);
            assertThat(ev.playerId()).isEqualTo(PLAYER);
            assertThat(ev.sessionId()).isEqualTo(sessionId);
            assertThat(ev.beforeStateHash()).isNotBlank().hasSize(64);
            assertThat(ev.afterStateHash()).isNotBlank().hasSize(64);
            // Hash must change between before and after - pick mutates state
            assertThat(ev.beforeStateHash()).isNotEqualTo(ev.afterStateHash());
            assertThat(ev.position()).isEqualTo(i);
            assertThat(ev.remainingPicksAfter()).isLessThanOrEqualTo(ev.remainingPicksBefore());
        }
        // Last event must mark completion
        PickAuditEvent last = listener.events.get(listener.events.size() - 1);
        assertThat(last.featureCompleted()).isTrue();
    }

    /** Test-scoped @EventListener that records each {@link PickAuditEvent} for assertions. */
    static class CapturingPickAuditListener {
        final List<PickAuditEvent> events = new ArrayList<>();

        @EventListener
        void capture(PickAuditEvent event) {
            events.add(event);
        }
    }

    @TestConfiguration
    static class CapturingConfig {
        @Bean
        CapturingPickAuditListener capturingPickAuditListener() {
            return new CapturingPickAuditListener();
        }
    }

    private JsonNode postJson(String url, String idemKey, String body) throws Exception {
        var req = post(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (idemKey != null) {
            req.header(IdempotencyAspect.HEADER_KEY, idemKey);
        }
        MvcResult res = mockMvc.perform(req).andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        return mapper.readTree(res.getResponse().getContentAsString());
    }

    private String jwt() {
        return JwtTestFactory.validToken(PLAYER, "ses-" + UUID.randomUUID(), CURRENCY);
    }
}
