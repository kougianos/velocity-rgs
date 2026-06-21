package com.velocity.rgs.game.service;

import com.velocity.rgs.game.feature.pickcollect.PickCollectEngine;
import com.velocity.rgs.game.feature.pickcollect.PickCollectState;
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
 * <p>Bypasses wallet/session persistence so 100k+ rounds complete in seconds. Three channels are
 * sampled independently: BASE_GAME (with naturally triggered free spins folded into the win-only
 * column), BONUS_BUY_FREE_SPINS (free-spins purchase) and BONUS_BUY_PICK_COLLECT (Pick &amp; Collect
 * purchase). Each channel's RTP = totalWin / totalBet * 100. The {@code overall} channel is the
 * wager-weighted aggregate across all non-empty channels.
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
        Aggregator buyPc = new Aggregator();
        LongAdder freeSpinTriggers = new LongAdder();
        LongAdder pickEntries = new LongAdder();

        for (long i = 0; i < request.spinsBaseGame(); i++) {
            simulateBaseSpin(math, newRng(), request.bet(), base, freeSpinTriggers);
        }
        for (long i = 0; i < request.spinsPowerBet(); i++) {
            simulatePowerBetSpin(math, newRng(), request.bet(), powerBet, freeSpinTriggers);
        }
        for (long i = 0; i < request.spinsBonusBuyFreeSpins(); i++) {
            simulateBonusBuyFreeSpins(math, newRng(), request.bet(), buyFs);
        }
        for (long i = 0; i < request.spinsBonusBuyPickCollect(); i++) {
            simulateBonusBuyPickCollect(math, newRng(), request.bet(),
                    request.pickStrategy(), buyPc, pickEntries);
        }

        Map<String, RtpReport.Channel> channels = new LinkedHashMap<>();
        channels.put("BASE_GAME", base.snapshot());
        channels.put("POWER_BET", powerBet.snapshot());
        channels.put("BONUS_BUY_FREE_SPINS", buyFs.snapshot());
        channels.put("BONUS_BUY_PICK_COLLECT", buyPc.snapshot());

        RtpReport.Channel overall = overall(base, powerBet, buyFs, buyPc);
        long elapsed = System.currentTimeMillis() - start;

        log.info("RTP simulation runId={} game={}/{} bet={} elapsedMs={} BASE={}% POWER={}% FS_BUY={}% PICK_BUY={}% OVERALL={}%",
                effectiveRunId, request.gameId(), request.mathVersion(), request.bet(), elapsed,
                channels.get("BASE_GAME").rtpPercent(), channels.get("POWER_BET").rtpPercent(),
                channels.get("BONUS_BUY_FREE_SPINS").rtpPercent(),
                channels.get("BONUS_BUY_PICK_COLLECT").rtpPercent(), overall.rtpPercent());

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
                                  Aggregator base, LongAdder freeSpinTriggers) {
        GridGenerationResult grid = gridEngine.generate(math, ReelStripSet.BASE, rng);
        EvaluationResult eval = reelEvaluator.evaluate(grid.matrix(), bet, math);
        base.record(bet, eval.totalWin());
        int scatters = countScatters(grid.matrix(), math);
        if (scatters >= math.scatterTriggers().minCount()) {
            freeSpinTriggers.increment();
            BigDecimal freeWin = simulateFreeSpins(math, rng, bet, math.scatterTriggers().freeSpinsAwarded());
            base.recordWinOnly(freeWin);
        }
    }

    /**
     * Power-bet base spins: stake is raised by {@code powerBet.betMultiplier} and the richer
     * {@link ReelStripSet#POWER_BET} strip is used. Wins (line wins + any naturally triggered free
     * spins) are evaluated against the same multiplied stake, mirroring the live spin path so the
     * reported RTP reflects what the player actually experiences on a power bet.
     */
    private void simulatePowerBetSpin(SlotMathDefinition math, RandomNumberGenerator rng, BigDecimal bet,
                                      Aggregator powerBet, LongAdder freeSpinTriggers) {
        BigDecimal stake = bet.multiply(math.powerBet().betMultiplier());
        GridGenerationResult grid = gridEngine.generate(math, ReelStripSet.POWER_BET, rng);
        EvaluationResult eval = reelEvaluator.evaluate(grid.matrix(), stake, math);
        powerBet.record(stake, eval.totalWin());
        int scatters = countScatters(grid.matrix(), math);
        if (scatters >= math.scatterTriggers().minCount()) {
            freeSpinTriggers.increment();
            BigDecimal freeWin = simulateFreeSpins(math, rng, stake, math.scatterTriggers().freeSpinsAwarded());
            powerBet.recordWinOnly(freeWin);
        }
    }

    private void simulateBonusBuyFreeSpins(SlotMathDefinition math, RandomNumberGenerator rng,
                                           BigDecimal bet, Aggregator agg) {
        BigDecimal cost = buyCost(math, BonusBuyType.FREE_SPINS_BUY, bet);
        BigDecimal win = simulateFreeSpins(math, rng, bet, math.scatterTriggers().freeSpinsAwarded());
        agg.record(cost, win);
    }

    private void simulateBonusBuyPickCollect(SlotMathDefinition math, RandomNumberGenerator rng,
                                             BigDecimal bet, RtpSimulationRequest.PickStrategy strategy,
                                             Aggregator agg, LongAdder pickEntries) {
        BigDecimal cost = buyCost(math, BonusBuyType.PICK_COLLECT_BUY, bet);
        PickCollectState pickState = pickCollectEngine.startFeature(math.pickCollect(), bet, rng,
                math.pickCollect().completion().value());
        pickEntries.increment();
        int seqCursor = 0;
        while (pickState.status() == PickCollectState.Status.ACTIVE) {
            int next = nextPick(pickState, rng, strategy, seqCursor);
            seqCursor = next + 1;
            pickCollectEngine.applyPick(pickState, next, math.pickCollect());
        }
        PickCollectEngine.FinalizationResult fin = pickCollectEngine.finalizeFeature(pickState,
                math.pickCollect(), "EUR");
        agg.record(cost, fin.finalWin().amount());
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

    private BigDecimal buyCost(SlotMathDefinition math, BonusBuyType type, BigDecimal bet) {
        return math.bonusBuyOptions().stream()
                .filter(o -> o.buyType() == type)
                .findFirst()
                .map(o -> bet.multiply(o.costMultiplier()))
                .orElseThrow(() -> new IllegalStateException("No bonus-buy option configured for " + type));
    }

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
