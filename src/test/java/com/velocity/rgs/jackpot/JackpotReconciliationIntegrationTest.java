package com.velocity.rgs.jackpot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocity.rgs.audit.AuditReconciliationFinding;
import com.velocity.rgs.audit.AuditReconciliationFindingRepository;
import com.velocity.rgs.audit.reconciliation.ReconciliationJob;
import com.velocity.rgs.common.idempotency.IdempotencyAspect;
import com.velocity.rgs.jackpot.domain.JackpotTier;
import com.velocity.rgs.jackpot.persistence.JackpotWinRepository;
import com.velocity.rgs.slot.math.config.ProgressiveJackpotConfig;
import com.velocity.rgs.testsupport.JwtTestFactory;
import com.velocity.rgs.testsupport.RgsIntegrationTest;
import com.velocity.rgs.wallet.api.WalletCreditRequest;
import com.velocity.rgs.wallet.domain.WalletTransactionType;
import com.velocity.rgs.wallet.gateway.WalletGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * A jackpot payout reconciling against the ledger (§2).
 *
 * <p>A progressive is credited on its own transaction and is not part of the round's {@code total_win},
 * so the reconciliation job sees a credit the game view never expected. Left alone, every jackpot ever
 * won would raise a discrepancy the size of a whole pool - the audit row is what explains it.
 *
 * <p>The negative case below is the important one. A test that only checked "no findings" would pass
 * identically against a job that had stopped detecting anything at all, so this also seeds a genuinely
 * unexplained credit and proves the same run still catches it.
 */
@RgsIntegrationTest
@AutoConfigureMockMvc
class JackpotReconciliationIntegrationTest {

    private static final String GAME = "aztec-fire";
    private static final String CURRENCY = "EUR";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JackpotService jackpotService;
    @Autowired private JackpotWinRepository winRepository;
    @Autowired private ReconciliationJob reconciliationJob;
    @Autowired private AuditReconciliationFindingRepository findingRepository;
    @Autowired private WalletGateway walletGateway;

    private Instant bucketStart;
    private Instant bucketEnd;

    @BeforeEach
    void openBucket() {
        // A window wide enough to contain whatever this test does, and its own findings are cleared so
        // an earlier test's discrepancy cannot be mistaken for one of ours.
        bucketStart = Instant.now().minus(1, ChronoUnit.HOURS);
        bucketEnd = Instant.now().plus(1, ChronoUnit.HOURS);
        findingRepository.deleteAll();
    }

    /**
     * The headline: winning a jackpot leaves the player reconciling cleanly.
     *
     * <p>Scoped to this player's findings rather than asserting the whole table is empty - the suite
     * shares a database, and other tests' players are not this test's business.
     */
    @Test
    void aJackpotWinReconcilesInsteadOfBeingFlagged() throws Exception {
        String player = player();
        String sessionId = init(player);
        jackpotService.forceNextWin(player, JackpotTier.MINOR);
        spin(player, sessionId, "1.00");

        assertThat(winRepository.findByPlayerIdOrderByWonAtDesc(player))
                .as("precondition: the jackpot was actually awarded").hasSize(1);

        List<AuditReconciliationFinding> findings = findingsFor(player);

        assertThat(findings)
                .as("a jackpot is explained by its audit row, not flagged as an unexplained credit")
                .isEmpty();
    }

    /**
     * The control: the same run still catches a credit nothing explains.
     *
     * <p>Without this, "no findings" would be indistinguishable from a job that had quietly stopped
     * working - which is the failure mode an audit report can least afford.
     */
    @Test
    void anUnexplainedCreditIsStillCaught() throws Exception {
        String player = player();
        String sessionId = init(player);
        spin(player, sessionId, "1.00");

        // Money into the wallet with no round, no feature purchase and no jackpot row behind it.
        walletGateway.credit(new WalletCreditRequest(player, sessionId,
                        "round-that-does-not-exist", "unexplained-" + UUID.randomUUID(),
                        new BigDecimal("250.00"), CURRENCY, WalletTransactionType.WIN),
                "unexplained-key-" + UUID.randomUUID(), CURRENCY);

        List<AuditReconciliationFinding> findings = findingsFor(player);

        assertThat(findings).hasSize(1);
        AuditReconciliationFinding finding = findings.get(0);
        assertThat(finding.getDiscrepancyKind())
                .isEqualTo(AuditReconciliationFinding.DiscrepancyKind.CREDIT_MISMATCH);
        assertThat(finding.getActualCredit())
                .as("the ledger holds more than the game view can account for")
                .isGreaterThan(finding.getExpectedCredit());
    }

    /**
     * A jackpot is expected at the amount actually paid, not the amount the pool held.
     *
     * <p>The two differ by the sub-cent remainder the wallet cannot hold, so reconciling against the
     * pool figure instead of the payout would raise a finding on every award for a fraction of a cent.
     */
    @Test
    void theExpectationMatchesWhatWasPaidNotWhatThePoolHeld() throws Exception {
        String player = player();
        String sessionId = init(player);
        // Grown through the service so this test spins exactly once: a warm-up spin can trigger a
        // feature, leave the session out of base game and turn the winning spin into a 409.
        jackpotService.contribute(new ProgressiveJackpotConfig(true, null),
                CURRENCY, new BigDecimal("50.00"));
        jackpotService.forceNextWin(player, JackpotTier.MAJOR);
        spin(player, sessionId, "5.00");

        BigDecimal paid = winRepository.findByPlayerIdOrderByWonAtDesc(player).get(0).getAmount();
        // Trailing zeros stripped first: the column is NUMERIC(19,4), so a clean 1,004.24 reads back as
        // 1004.2400 and its raw scale describes the column rather than the value. What matters is that
        // there is no fractional-cent component - the row records what the wallet was credited.
        assertThat(paid.stripTrailingZeros().scale())
                .as("the audit row records the credited figure, not the pool's four-decimal balance")
                .isLessThanOrEqualTo(2);

        assertThat(findingsFor(player)).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    private List<AuditReconciliationFinding> findingsFor(String player) {
        return reconciliationJob.runForBucket(bucketStart, bucketEnd).stream()
                .filter(f -> player.equals(f.getPlayerId()))
                .toList();
    }

    private String player() {
        return "p-recon-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String init(String player) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/slot/init")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(player))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.createObjectNode()
                                .put("gameId", GAME).put("currency", CURRENCY).toString()))
                .andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        return mapper.readTree(res.getResponse().getContentAsString()).get("sessionId").asText();
    }

    private void spin(String player, String sessionId, String bet) throws Exception {
        long version = mapper.readTree(mockMvc.perform(post("/api/v1/slot/init")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(player))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.createObjectNode()
                                .put("gameId", GAME).put("currency", CURRENCY).toString()))
                .andReturn().getResponse().getContentAsString()).get("sessionVersion").asLong();

        MvcResult res = mockMvc.perform(post("/api/v1/slot/spin")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(player))
                        .header(IdempotencyAspect.HEADER_KEY, "recon-" + UUID.randomUUID())
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
