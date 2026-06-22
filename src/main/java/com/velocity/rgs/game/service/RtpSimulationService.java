package com.velocity.rgs.game.service;

import com.velocity.rgs.game.feature.pickcollect.PickCollectEngine;
import com.velocity.rgs.game.feature.pickcollect.PickCollectState;
import com.velocity.rgs.math.config.BonusBuyOption;
import com.velocity.rgs.math.config.SlotMathDefinition;
import com.velocity.rgs.math.config.SlotMathRegistry;
import com.velocity.rgs.math.domain.BonusBuyType;
import com.velocity.rgs.math.domain.ReelStripSet;
import com.velocity.rgs.math.domain.SymbolType;
import com.velocity.rgs.math.engine.EvaluationResult;
import com.velocity.rgs.math.engine.GridGenerationEngine;
import com.velocity.rgs.math.engine.GridGenerationResult;
import com.velocity.rgs.math.engine.ReelEvaluator;
import com.velocity.rgs.rng.RandomNumberGenerator;
import com.velocity.rgs.rng.RngDrawSink;
import com.velocity.rgs.rng.SecureRandomNumberGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.LongAdder;

/**
 * Pure-math RTP simulator harness exposed both as a CLI runner ({@link RtpSimulator}) and as the
 * synchronous service backing {@code POST /api/v1/admin/simulator/run} (M7 Task 7.6 / A.19).
 *
 * <p>Bypasses wallet/session persistence so 100k+ rounds complete in seconds. Channels are sampled
 * independently: BASE_GAME and POWER_BET (each with naturally triggered free spins <em>and</em>
 * organically triggered Pick &amp; Collect folded into the win-only column) and BONUS_BUY_FREE_SPINS
 * (the one remaining purchasable feature). Each channel's RTP = totalWin / totalBet * 100. The
 * {@code overall} channel is the wager-weighted aggregate across all non-empty channels.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RtpSimulationService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final SlotMathRegistry mathRegistry;
    private final GridGenerationEngine gridEngine;
    private final ReelEvaluator reelEvaluator;
    private final PickCollectEngine pickCollectEngine;

    public RtpReport run(RtpSimulationRequest request, String runId) {
        SlotMathDefinition math = mathRegistry.require(request.gameId(), request.mathVersion());
        long start = System.currentTimeMillis();
        String effectiveRunId = runId != null && !runId.isBlank() ? runId : UUID.randomUUID().toString();

        Aggregator base = new Aggregator();
        Aggregator powerBet = new Aggregator();
        Aggregator buyFs = new Aggregator();
        LongAdder freeSpinTriggers = new LongAdder();
        LongAdder pickEntries = new LongAdder();

        for (long i = 0; i < request.spinsBaseGame(); i++) {
            simulateBaseSpin(math, newRng(), request.bet(), base, freeSpinTriggers, pickEntries,
                    request.pickStrategy());
        }
        for (long i = 0; i < request.spinsPowerBet(); i++) {
            simulatePowerBetSpin(math, newRng(), request.bet(), powerBet, freeSpinTriggers, pickEntries,
                    request.pickStrategy());
        }
        for (long i = 0; i < request.spinsBonusBuyFreeSpins(); i++) {
            simulateBonusBuyFreeSpins(math, newRng(), request.bet(), buyFs);
        }

        Map<String, RtpReport.Channel> channels = new LinkedHashMap<>();
        channels.put("BASE_GAME", base.snapshot());
        channels.put("POWER_BET", powerBet.snapshot());
        channels.put("BONUS_BUY_FREE_SPINS", buyFs.snapshot());

        RtpReport.Channel overall = overall(base, powerBet, buyFs);
        long elapsed = System.currentTimeMillis() - start;

        log.info("RTP simulation runId={} game={}/{} bet={} elapsedMs={} BASE={}% POWER={}% FS_BUY={}% OVERALL={}% pickEntries={}",
                effectiveRunId, request.gameId(), request.mathVersion(), request.bet(), elapsed,
                channels.get("BASE_GAME").rtpPercent(), channels.get("POWER_BET").rtpPercent(),
                channels.get("BONUS_BUY_FREE_SPINS").rtpPercent(), overall.rtpPercent(), pickEntries.sum());

        return RtpReport.builder()
                .runId(effectiveRunId)
                .gameId(request.gameId())
                .mathVersion(request.mathVersion())
                .bet(request.bet())
                .channels(channels)
                .overall(overall)
                .elapsedMillis(elapsed)
                .generatedAt(Instant.now())
                .freeSpinTriggers(freeSpinTriggers.sum())
                .pickEntries(pickEntries.sum())
                .build();
    }

    // ------------------------------------------------------------------ channels

    private void simulateBaseSpin(SlotMathDefinition math, RandomNumberGenerator rng, BigDecimal bet,
                                  Aggregator base, LongAdder freeSpinTriggers, LongAdder pickEntries,
                                  RtpSimulationRequest.PickStrategy strategy) {
        GridGenerationResult grid = gridEngine.generate(math, ReelStripSet.BASE, rng);
        EvaluationResult eval = reelEvaluator.evaluate(grid.matrix(), bet, math);
        base.record(bet, eval.totalWin());
        int scatters = countScatters(grid.matrix(), math);
        if (scatters >= math.scatterTriggers().minCount()) {
            freeSpinTriggers.increment();
            BigDecimal freeWin = simulateFreeSpins(math, rng, bet, math.scatterTriggers().freeSpinsAwarded());
            base.recordWinOnly(freeWin);
        } else if (rollPickCollectTrigger(math, rng)) {
            // Pick & Collect is triggered organically (and never on the same spin that awards free
            // spins). Its win is funded by base wagers, so it folds into the BASE_GAME channel.
            pickEntries.increment();
            base.recordWinOnly(simulatePickCollect(math, rng, bet, strategy));
        }
    }

    /**
     * Power-bet base spins: stake is raised by {@code powerBet.betMultiplier} and the richer
     * {@link ReelStripSet#POWER_BET} strip is used. Wins (line wins + any naturally triggered free
     * spins) are evaluated against the same multiplied stake, mirroring the live spin path so the
     * reported RTP reflects what the player actually experiences on a power bet.
     */
    private void simulatePowerBetSpin(SlotMathDefinition math, RandomNumberGenerator rng, BigDecimal bet,
                                      Aggregator powerBet, LongAdder freeSpinTriggers, LongAdder pickEntries,
                                      RtpSimulationRequest.PickStrategy strategy) {
        BigDecimal stake = bet.multiply(math.powerBet().betMultiplier());
        GridGenerationResult grid = gridEngine.generate(math, ReelStripSet.POWER_BET, rng);
        EvaluationResult eval = reelEvaluator.evaluate(grid.matrix(), stake, math);
        powerBet.record(stake, eval.totalWin());
        int scatters = countScatters(grid.matrix(), math);
        if (scatters >= math.scatterTriggers().minCount()) {
            freeSpinTriggers.increment();
            BigDecimal freeWin = simulateFreeSpins(math, rng, stake, math.scatterTriggers().freeSpinsAwarded());
            powerBet.recordWinOnly(freeWin);
        } else if (rollPickCollectTrigger(math, rng)) {
            pickEntries.increment();
            powerBet.recordWinOnly(simulatePickCollect(math, rng, stake, strategy));
        }
    }

    private void simulateBonusBuyFreeSpins(SlotMathDefinition math, RandomNumberGenerator rng,
                                           BigDecimal bet, Aggregator agg) {
        BonusBuyOption option = freeSpinsBuyOption(math);
        BigDecimal cost = bet.multiply(option.costMultiplier());
        int spins = bonusBuyFreeSpins(math, option);
        BigDecimal win = simulateFreeSpins(math, rng, bet, spins)
                .multiply(option.freeSpinsWinMultiplier());
        agg.record(cost, win);
    }

    private BonusBuyOption freeSpinsBuyOption(SlotMathDefinition math) {
        return math.bonusBuyOptions().stream()
                .filter(o -> o.buyType() == BonusBuyType.FREE_SPINS_BUY)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No bonus-buy option configured for " + BonusBuyType.FREE_SPINS_BUY));
    }

    /**
     * Free spins awarded by the {@code FREE_SPINS_BUY} bonus buy. Read from the buy option's
     * {@code initialFeaturePayload.freeSpinsAwarded}, exactly as the live path does
     * ({@code SessionStateMachine.extractFreeSpinsAwarded}). The bought feature keeps an industry-standard
     * spin count (~10–15) and is made richer per spin via {@code freeSpinsWinMultiplier} rather than
     * longer, mirroring {@code SlotEngineService}'s settlement boost. Falls back to the organic count if
     * no payload is present.
     */
    private int bonusBuyFreeSpins(SlotMathDefinition math, BonusBuyOption option) {
        Object raw = option.initialFeaturePayload().get("freeSpinsAwarded");
        return raw instanceof Number n ? n.intValue() : math.scatterTriggers().freeSpinsAwarded();
    }

    /** One per-spin draw of the organic Pick &amp; Collect trigger ({@code 1 in triggerOneInN}). */
    private boolean rollPickCollectTrigger(SlotMathDefinition math, RandomNumberGenerator rng) {
        if (!math.pickCollect().organicTriggerEnabled()) {
            return false;
        }
        return rng.nextIndex(math.pickCollect().triggerOneInN()) == 0;
    }

    /** Plays one Pick &amp; Collect feature to completion and returns the finalized win amount. */
    private BigDecimal simulatePickCollect(SlotMathDefinition math, RandomNumberGenerator rng,
                                           BigDecimal bet, RtpSimulationRequest.PickStrategy strategy) {
        PickCollectState pickState = pickCollectEngine.startFeature(math.pickCollect(), bet, rng, 0);
        int seqCursor = 0;
        while (pickState.status() == PickCollectState.Status.ACTIVE) {
            int next = nextPick(pickState, rng, strategy, seqCursor);
            seqCursor = next + 1;
            pickCollectEngine.applyPick(pickState, next, math.pickCollect());
        }
        return pickCollectEngine.finalizeFeature(pickState, math.pickCollect(), "EUR").finalWin().amount();
    }

    private BigDecimal simulateFreeSpins(SlotMathDefinition math, RandomNumberGenerator rng,
                                         BigDecimal triggerBet, int initialSpins) {
        BigDecimal total = BigDecimal.ZERO;
        int remaining = initialSpins;
        while (remaining > 0) {
            GridGenerationResult grid = gridEngine.generate(math, ReelStripSet.FREE_SPINS, rng);
            EvaluationResult eval = reelEvaluator.evaluate(grid.matrix(), triggerBet, math);
            total = total.add(eval.totalWin());
            int scatters = countScatters(grid.matrix(), math);
            remaining--;
            if (scatters >= math.scatterTriggers().minCount()) {
                remaining += math.scatterTriggers().retriggerAwards();
            }
        }
        return total;
    }

    // ------------------------------------------------------------------ helpers

    private int nextPick(PickCollectState state, RandomNumberGenerator rng,
                         RtpSimulationRequest.PickStrategy strategy, int seqCursor) {
        return switch (strategy) {
            case SEQUENTIAL -> firstUnopenedFrom(state, seqCursor);
            case RANDOM_UNOPENED -> randomUnopened(state, rng);
            case COLLECT_FIRST -> firstUnopenedFrom(state, 0);
        };
    }

    private int firstUnopenedFrom(PickCollectState state, int cursor) {
        int n = state.boardSize();
        for (int i = 0; i < n; i++) {
            int candidate = (cursor + i) % n;
            if (!state.openedPositions().contains(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("No unopened pick positions remain");
    }

    private int randomUnopened(PickCollectState state, RandomNumberGenerator rng) {
        int boardSize = state.boardSize();
        for (int attempt = 0; attempt < boardSize * 4; attempt++) {
            int candidate = rng.nextIndex(boardSize);
            if (!state.openedPositions().contains(candidate)) {
                return candidate;
            }
        }
        return firstUnopenedFrom(state, 0);
    }

    private int countScatters(int[][] matrix, SlotMathDefinition math) {
        int scatterId = math.symbols().stream()
                .filter(s -> s.type() == SymbolType.SCATTER)
                .mapToInt(s -> s.id())
                .findFirst()
                .orElse(-1);
        if (scatterId < 0) return 0;
        int count = 0;
        for (int[] col : matrix) {
            for (int sym : col) {
                if (sym == scatterId) count++;
            }
        }
        return count;
    }

    private RtpReport.Channel overall(Aggregator... aggs) {
        BigDecimal totalBet = BigDecimal.ZERO;
        BigDecimal totalWin = BigDecimal.ZERO;
        long spins = 0;
        for (Aggregator a : aggs) {
            totalBet = totalBet.add(a.totalBet());
            totalWin = totalWin.add(a.totalWin());
            spins += a.rounds();
        }
        BigDecimal rtp = totalBet.signum() == 0 ? BigDecimal.ZERO
                : totalWin.multiply(HUNDRED).divide(totalBet, 4, RoundingMode.HALF_UP);
        return RtpReport.Channel.builder()
                .spins(spins).totalBet(totalBet).totalWin(totalWin).rtpPercent(rtp).build();
    }

    private RandomNumberGenerator newRng() {
        return new SecureRandomNumberGenerator(RngDrawSink.inMemory());
    }

    private static final class Aggregator {
        private final LongAdder rounds = new LongAdder();
        private BigDecimal totalBet = BigDecimal.ZERO;
        private BigDecimal totalWin = BigDecimal.ZERO;

        synchronized void record(BigDecimal bet, BigDecimal win) {
            rounds.increment();
            totalBet = totalBet.add(bet);
            totalWin = totalWin.add(win);
        }

        synchronized void recordWinOnly(BigDecimal win) {
            totalWin = totalWin.add(win);
        }

        synchronized BigDecimal totalBet() { return totalBet; }
        synchronized BigDecimal totalWin() { return totalWin; }
        synchronized long rounds() { return rounds.sum(); }

        synchronized RtpReport.Channel snapshot() {
            BigDecimal rtp = totalBet.signum() == 0 ? BigDecimal.ZERO
                    : totalWin.multiply(HUNDRED).divide(totalBet, 4, RoundingMode.HALF_UP);
            return RtpReport.Channel.builder()
                    .spins(rounds.sum())
                    .totalBet(totalBet)
                    .totalWin(totalWin)
                    .rtpPercent(rtp)
                    .build();
        }
    }
}
