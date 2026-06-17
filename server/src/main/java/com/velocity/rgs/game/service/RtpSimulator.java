package com.velocity.rgs.game.service;

import com.velocity.rgs.game.feature.pickcollect.PickCollectEngine;
import com.velocity.rgs.game.feature.pickcollect.PickCollectState;
import com.velocity.rgs.math.config.SlotMathDefinition;
import com.velocity.rgs.math.config.SlotMathRegistry;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.atomic.LongAdder;

/**
 * RTP simulator (M5 Task 5.9). Runs {@code rgs.simulator.spins} spins against the pure math
 * components, aggregates the three RTP channels mandated by Section 3.10 — {@code BASE_GAME_RTP},
 * {@code BONUS_BUY_RTP}, {@code OVERALL_RTP} — and prints the summary to the application log on
 * startup. Active only when the {@code simulator} profile is enabled.
 *
 * <p>This harness intentionally bypasses the wallet/session persistence layer (it operates on the
 * deterministic math + RNG stack only) so it can churn 100k+ rounds in a few seconds. It is not
 * suitable for ledger-level verification — that is covered by the integration test suite.
 */
@Slf4j
@Component
@Profile("simulator")
@RequiredArgsConstructor
public class RtpSimulator implements CommandLineRunner {

    private final SlotMathRegistry mathRegistry;
    private final GridGenerationEngine gridEngine;
    private final ReelEvaluator reelEvaluator;
    private final PickCollectEngine pickCollectEngine;

    @Value("${rgs.simulator.spins:100000}")
    private long spins;

    @Value("${rgs.simulator.gameId:aztec-fire}")
    private String gameId;

    @Value("${rgs.simulator.mathVersion:v1}")
    private String mathVersion;

    @Value("${rgs.simulator.bet:1.00}")
    private BigDecimal bet;

    @Override
    public void run(String... args) {
        SlotMathDefinition math = mathRegistry.require(gameId, mathVersion);
        log.info("RTP simulator: starting {} spins on {}/{} (bet={})", spins, gameId, mathVersion, bet);

        Aggregator base = new Aggregator();
        Aggregator bonusBuy = new Aggregator();
        LongAdder freeSpinTriggers = new LongAdder();
        LongAdder pickEntries = new LongAdder();

        for (long i = 0; i < spins; i++) {
            RandomNumberGenerator rng = new SecureRandomNumberGenerator(RngDrawSink.inMemory());
            simulateBaseSpin(math, rng, base, freeSpinTriggers);
        }

        long bonusBuyRounds = Math.max(1, spins / 50);
        for (long i = 0; i < bonusBuyRounds; i++) {
            RandomNumberGenerator rng = new SecureRandomNumberGenerator(RngDrawSink.inMemory());
            simulateBonusBuy(math, rng, bonusBuy, pickEntries);
        }

        BigDecimal baseRtp = base.rtp();
        BigDecimal bonusBuyRtp = bonusBuy.rtp();
        BigDecimal overallBet = base.totalBet().add(bonusBuy.totalBet());
        BigDecimal overallWin = base.totalWin().add(bonusBuy.totalWin());
        BigDecimal overallRtp = overallBet.signum() == 0 ? BigDecimal.ZERO
                : overallWin.multiply(BigDecimal.valueOf(100))
                        .divide(overallBet, 4, RoundingMode.HALF_UP);

        log.info("RTP simulator result (spins={}): BASE_GAME_RTP={}% BONUS_BUY_RTP={}% OVERALL_RTP={}% "
                        + "(freeSpinTriggers={}, pickEntries={})",
                spins, baseRtp, bonusBuyRtp, overallRtp, freeSpinTriggers.sum(), pickEntries.sum());
    }

    private void simulateBaseSpin(SlotMathDefinition math, RandomNumberGenerator rng,
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

    private void simulateBonusBuy(SlotMathDefinition math, RandomNumberGenerator rng,
                                  Aggregator bonusBuy, LongAdder pickEntries) {
        BigDecimal cost = bet.multiply(BigDecimal.valueOf(80));
        BigDecimal win = simulateFreeSpins(math, rng, bet, math.scatterTriggers().freeSpinsAwarded());
        bonusBuy.record(cost, win);

        BigDecimal pickCost = bet.multiply(BigDecimal.valueOf(120));
        PickCollectState pickState = pickCollectEngine.startFeature(math.pickCollect(), bet, rng,
                math.pickCollect().completion().value());
        pickEntries.increment();
        while (pickState.status() == PickCollectState.Status.ACTIVE) {
            int next = nextUnopened(pickState, rng);
            pickCollectEngine.applyPick(pickState, next, math.pickCollect());
        }
        PickCollectEngine.FinalizationResult fin = pickCollectEngine.finalizeFeature(pickState,
                math.pickCollect(), "EUR");
        bonusBuy.record(pickCost, fin.finalWin().amount());
    }

    private int nextUnopened(PickCollectState state, RandomNumberGenerator rng) {
        int boardSize = state.boardSize();
        while (true) {
            int candidate = rng.nextIndex(boardSize);
            if (!state.openedPositions().contains(candidate)) {
                return candidate;
            }
        }
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

        synchronized BigDecimal rtp() {
            if (totalBet.signum() == 0) return BigDecimal.ZERO;
            return totalWin.multiply(BigDecimal.valueOf(100))
                    .divide(totalBet, 4, RoundingMode.HALF_UP);
        }
    }
}
