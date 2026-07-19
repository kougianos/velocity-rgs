package com.velocity.rgs.slot.service;

import com.velocity.rgs.common.error.ErrorCode;
import com.velocity.rgs.common.error.RgsException;
import com.velocity.rgs.slot.feature.pickcollect.PickCollectEngine;
import com.velocity.rgs.slot.feature.pickcollect.PickCollectState;
import com.velocity.rgs.slot.feature.respin.RespinEngine;
import com.velocity.rgs.slot.feature.respin.RespinState;
import com.velocity.rgs.slot.math.config.BonusBuyOption;
import com.velocity.rgs.slot.math.config.SlotMathDefinition;
import com.velocity.rgs.slot.math.config.SlotMathRegistry;
import com.velocity.rgs.slot.math.domain.BonusBuyType;
import com.velocity.rgs.slot.math.domain.ReelStripSet;
import com.velocity.rgs.slot.math.domain.SymbolType;
import com.velocity.rgs.slot.math.engine.EvaluationResult;
import com.velocity.rgs.slot.math.engine.GridGenerationEngine;
import com.velocity.rgs.slot.math.engine.GridGenerationResult;
import com.velocity.rgs.slot.math.engine.ReelEvaluator;
import com.velocity.rgs.slot.math.engine.WildFeatureEngine;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.IntStream;

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
    private final RespinEngine respinEngine;
    private final WildFeatureEngine wildFeatureEngine;

    public RtpReport run(RtpSimulationRequest request, String runId) {
        SlotMathDefinition math = mathRegistry.require(request.gameId(), request.mathVersion());
        long start = System.currentTimeMillis();
        String effectiveRunId = runId != null && !runId.isBlank() ? runId : UUID.randomUUID().toString();

        LongAdder freeSpinTriggers = new LongAdder();
        LongAdder pickEntries = new LongAdder();
        LongAdder respinEntries = new LongAdder();

        Aggregator base = simulateChannel(request.spinsBaseGame(), (rng, agg) ->
                simulateBaseSpin(math, rng, request.bet(), agg, freeSpinTriggers, pickEntries,
                        respinEntries, request.pickStrategy()));
        Aggregator powerBet = simulateChannel(request.spinsPowerBet(), (rng, agg) ->
                simulatePowerBetSpin(math, rng, request.bet(), agg, freeSpinTriggers, pickEntries,
                        respinEntries, request.pickStrategy()));
        Aggregator buyFs;
        if (request.spinsBonusBuyFreeSpins() > 0) {
            BonusBuyOption buyOption = requireBuyOption(math, BonusBuyType.FREE_SPINS_BUY);
            buyFs = simulateChannel(request.spinsBonusBuyFreeSpins(), (rng, agg) ->
                    simulateBonusBuyFreeSpins(math, buyOption, rng, request.bet(), agg));
        } else {
            buyFs = new Aggregator();
        }
        Aggregator buyHoldSpin;
        if (request.spinsBonusBuyHoldSpin() > 0) {
            BonusBuyOption buyOption = requireBuyOption(math, BonusBuyType.HOLD_SPIN_BUY);
            buyHoldSpin = simulateChannel(request.spinsBonusBuyHoldSpin(), (rng, agg) ->
                    simulateBonusBuyHoldSpin(math, buyOption, rng, request.bet(), agg));
        } else {
            buyHoldSpin = new Aggregator();
        }

        Map<String, RtpReport.Channel> channels = new LinkedHashMap<>();
        channels.put("BASE_GAME", base.snapshot());
        channels.put("POWER_BET", powerBet.snapshot());
        channels.put("BONUS_BUY_FREE_SPINS", buyFs.snapshot());
        channels.put("BONUS_BUY_HOLD_SPIN", buyHoldSpin.snapshot());

        RtpReport.Channel overall = overall(base, powerBet, buyFs, buyHoldSpin);
        long elapsed = System.currentTimeMillis() - start;

        log.info("RTP simulation runId={} game={}/{} bet={} elapsedMs={} BASE={}% POWER={}% FS_BUY={}% "
                        + "OVERALL={}% pickEntries={} respinEntries={}",
                effectiveRunId, request.gameId(), request.mathVersion(), request.bet(), elapsed,
                channels.get("BASE_GAME").rtpPercent(), channels.get("POWER_BET").rtpPercent(),
                channels.get("BONUS_BUY_FREE_SPINS").rtpPercent(), overall.rtpPercent(),
                pickEntries.sum(), respinEntries.sum());

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
                .respinEntries(respinEntries.sum())
                .build();
    }

    /**
     * Runs one channel's rounds across the available cores and merges the per-worker tallies.
     *
     * <p>Each worker owns its {@link Aggregator} and its RNG, so the hot loop touches no shared mutable
     * state. That is the point: the aggregator used to be {@code synchronized} on every method, and
     * sharing one across workers would serialise every round on its lock and hand back most of the
     * parallelism. Only {@code freeSpinTriggers} / {@code pickEntries} stay shared, and those are
     * {@link LongAdder}s touched on a small minority of rounds.
     *
     * <p>Statistically identical to running serially. The simulator is deliberately unseeded, so no
     * round's outcome ever depended on execution order, and totals are summed in a fixed worker order
     * at the end rather than accumulated in whatever order threads finish.
     */
    private Aggregator simulateChannel(long rounds, RoundSimulation round) {
        if (rounds <= 0) {
            return new Aggregator();
        }
        int workers = (int) Math.min(Runtime.getRuntime().availableProcessors(), rounds);
        List<Aggregator> partials = IntStream.range(0, workers)
                .parallel()
                .mapToObj(worker -> {
                    long share = rounds / workers + (worker < rounds % workers ? 1 : 0);
                    RandomNumberGenerator rng = newRng();
                    Aggregator local = new Aggregator();
                    for (long i = 0; i < share; i++) {
                        round.simulate(rng, local);
                    }
                    return local;
                })
                .toList();

        Aggregator combined = new Aggregator();
        partials.forEach(combined::merge);
        return combined;
    }

    /** One simulated round, played against a worker-local RNG and tally. */
    @FunctionalInterface
    private interface RoundSimulation {
        void simulate(RandomNumberGenerator rng, Aggregator aggregator);
    }

    // ------------------------------------------------------------------ channels

    private void simulateBaseSpin(SlotMathDefinition math, RandomNumberGenerator rng, BigDecimal bet,
                                  Aggregator base, LongAdder freeSpinTriggers, LongAdder pickEntries,
                                  LongAdder respinEntries, RtpSimulationRequest.PickStrategy strategy) {
        GridGenerationResult drawn = gridEngine.generate(math, ReelStripSet.BASE, rng);
        GridGenerationResult grid = new GridGenerationResult(
                wildFeatureEngine.apply(drawn.matrix(), math, ReelStripSet.BASE, List.of()).matrix(),
                drawn.stopPositions());
        EvaluationResult eval = reelEvaluator.evaluateRound(grid.matrix(), grid.stopPositions(), bet,
                math, ReelStripSet.BASE, rng);
        BigDecimal roundWin = eval.totalWin();
        int scatters = countScatters(grid.matrix(), math);
        // Trigger precedence mirrors SlotEngineService.postProcessSpin exactly: Hold & Spin outranks
        // the scatter award, which outranks the organic Pick & Collect roll. Diverging here would make
        // the simulator measure a game the live path does not deal.
        if (respinEngine.triggers(grid.matrix(), math.respins())) {
            respinEntries.increment();
            roundWin = roundWin.add(simulateRespins(math, rng, grid.matrix(), bet));
        } else if (scatters >= math.scatterTriggers().minCount()) {
            freeSpinTriggers.increment();
            roundWin = roundWin.add(
                    simulateFreeSpins(math, rng, bet, math.scatterTriggers().freeSpinsAwarded()));
        } else if (rollPickCollectTrigger(math, rng)) {
            // Pick & Collect is triggered organically (and never on the same spin that awards free
            // spins). Its win is funded by base wagers, so it folds into the BASE_GAME channel.
            pickEntries.increment();
            roundWin = roundWin.add(simulatePickCollect(math, rng, bet, strategy));
        }
        base.record(bet, roundWin);
    }

    /**
     * Power-bet base spins: stake is raised by {@code powerBet.betMultiplier} and the richer
     * {@link ReelStripSet#POWER_BET} strip is used. Wins (line wins + any naturally triggered free
     * spins) are evaluated against the same multiplied stake, mirroring the live spin path so the
     * reported RTP reflects what the player actually experiences on a power bet.
     */
    private void simulatePowerBetSpin(SlotMathDefinition math, RandomNumberGenerator rng, BigDecimal bet,
                                      Aggregator powerBet, LongAdder freeSpinTriggers, LongAdder pickEntries,
                                      LongAdder respinEntries,
                                      RtpSimulationRequest.PickStrategy strategy) {
        BigDecimal stake = bet.multiply(math.powerBet().betMultiplier());
        GridGenerationResult drawn = gridEngine.generate(math, ReelStripSet.POWER_BET, rng);
        GridGenerationResult grid = new GridGenerationResult(
                wildFeatureEngine.apply(drawn.matrix(), math, ReelStripSet.POWER_BET, List.of()).matrix(),
                drawn.stopPositions());
        EvaluationResult eval = reelEvaluator.evaluateRound(grid.matrix(), grid.stopPositions(), stake,
                math, ReelStripSet.POWER_BET, rng);
        BigDecimal roundWin = eval.totalWin();
        int scatters = countScatters(grid.matrix(), math);
        if (respinEngine.triggers(grid.matrix(), math.respins())) {
            respinEntries.increment();
            roundWin = roundWin.add(simulateRespins(math, rng, grid.matrix(), stake));
        } else if (scatters >= math.scatterTriggers().minCount()) {
            freeSpinTriggers.increment();
            roundWin = roundWin.add(
                    simulateFreeSpins(math, rng, stake, math.scatterTriggers().freeSpinsAwarded()));
        } else if (rollPickCollectTrigger(math, rng)) {
            pickEntries.increment();
            roundWin = roundWin.add(simulatePickCollect(math, rng, stake, strategy));
        }
        powerBet.record(stake, roundWin);
    }

    /**
     * Plays one Hold &amp; Spin feature to settlement and returns what it paid. Coins lock, the counter
     * refills whenever one lands, and the feature ends on an exhausted counter or a full grid - the
     * loop below is the same one {@code SlotEngineService.respinSpin} drives one HTTP call at a time.
     */
    private BigDecimal simulateRespins(SlotMathDefinition math, RandomNumberGenerator rng,
                                       int[][] triggerGrid, BigDecimal bet) {
        RespinState state = respinEngine.start(triggerGrid, math.respins(), rng);
        RespinEngine.RespinOutcome outcome;
        do {
            outcome = respinEngine.respin(state, math, ReelStripSet.BASE, rng);
            state = outcome.state();
        } while (!outcome.finished());
        return respinEngine.settle(state, math, bet, "EUR").win().amount();
    }

    private void simulateBonusBuyFreeSpins(SlotMathDefinition math, BonusBuyOption option,
                                           RandomNumberGenerator rng, BigDecimal bet, Aggregator agg) {
        BigDecimal cost = bet.multiply(option.costMultiplier());
        int spins = bonusBuyFreeSpins(math, option);
        BigDecimal win = simulateFreeSpins(math, rng, bet, spins)
                .multiply(option.freeSpinsWinMultiplier());
        agg.record(cost, win);
    }

    /**
     * One of the game's purchasable options. Resolved once per run rather than per spin, and only when
     * the caller actually asked for that channel - a game with no buy is still perfectly simulable on
     * its base and power-bet channels.
     *
     * <p>Raises {@code BONUS_BUY_DISABLED} (409) rather than an unchecked exception: requesting a
     * channel the game does not offer is a caller error, and letting it escape as an
     * {@link IllegalStateException} had {@link com.velocity.rgs.common.error.GlobalExceptionHandler}
     * report a 500. This mirrors the live spin path, which already fails this way in
     * {@code SessionStateMachine.findBuyOption}.
     */
    private BonusBuyOption requireBuyOption(SlotMathDefinition math, BonusBuyType type) {
        return math.bonusBuyOptions().stream()
                .filter(o -> o.buyType() == type)
                .findFirst()
                .orElseThrow(() -> new RgsException(ErrorCode.BONUS_BUY_DISABLED,
                        "Game " + math.gameId() + "@" + math.mathVersion() + " offers no "
                                + type + " option; request 0 bonus-buy spins for it"));
    }

    /**
     * One purchased Hold &amp; Spin: the buy locks {@code initialFeaturePayload.coins} coins, then the
     * feature runs to settlement exactly as a triggered one does.
     */
    private void simulateBonusBuyHoldSpin(SlotMathDefinition math, BonusBuyOption option,
                                          RandomNumberGenerator rng, BigDecimal bet, Aggregator agg) {
        BigDecimal cost = bet.multiply(option.costMultiplier());
        Object raw = option.initialFeaturePayload().get("coins");
        int coins = raw instanceof Number n ? n.intValue() : math.respins().triggerMinCount();

        RespinState state = respinEngine.startBought(math, coins, rng);
        RespinEngine.RespinOutcome outcome;
        do {
            outcome = respinEngine.respin(state, math, ReelStripSet.BASE, rng);
            state = outcome.state();
        } while (!outcome.finished());
        agg.record(cost, respinEngine.settle(state, math, bet, "EUR").win().amount());
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
        // Sticky and walking wilds persist across the feature's spins, so the carry has to be threaded
        // through the loop exactly as SlotEngineService threads it through active_feature_payload.
        // Dropping it here would have the simulator measure a materially poorer game than the one the
        // player is dealt.
        List<WildFeatureEngine.WildCell> carried = List.of();
        while (remaining > 0) {
            GridGenerationResult drawn = gridEngine.generate(math, ReelStripSet.FREE_SPINS, rng);
            WildFeatureEngine.WildOutcome wilds = wildFeatureEngine.apply(drawn.matrix(), math,
                    ReelStripSet.FREE_SPINS, carried);
            carried = wilds.carryForward();
            EvaluationResult eval = reelEvaluator.evaluateRound(wilds.matrix(), drawn.stopPositions(),
                    triggerBet, math, ReelStripSet.FREE_SPINS, rng);
            total = total.add(eval.totalWin());
            int scatters = countScatters(wilds.matrix(), math);
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

    /**
     * The wager-weighted aggregate across every sampled channel. Merging the tallies rather than
     * re-deriving from their snapshots keeps the hit count and band histogram exact: each round already
     * carries its own stake, so channels sampled at different effective bets combine without weighting.
     */
    private RtpReport.Channel overall(Aggregator... aggs) {
        Aggregator combined = new Aggregator();
        for (Aggregator a : aggs) {
            combined.merge(a);
        }
        return combined.snapshot();
    }

    /**
     * One RNG per worker, not per round. The live path builds one per round because that round's draws
     * <em>are</em> its audit trail; the simulator replays nothing, so it was paying for a
     * {@code new SecureRandom()} plus an unbounded capture list on every spin and reading neither back.
     * Measured over 300k spins, dropping both is a 1.92x speedup on its own - roughly half the cost of a
     * simulated spin was the RNG rather than the math.
     */
    private RandomNumberGenerator newRng() {
        return new SecureRandomNumberGenerator(RngDrawSink.discarding());
    }

    /**
     * Per-worker tally. Deliberately <em>not</em> thread-safe: each worker owns one and they are
     * {@link #merge merged} once at the end, which is what lets the hot loop run lock-free.
     *
     * <p>{@link #record} takes one <em>complete</em> round - stake and everything that round paid,
     * features included. That single-call shape is what makes the hit and band statistics meaningful:
     * counting a base spin and its free-spins award separately would report two rounds where the player
     * experienced one, inflating the round count and splitting one win across two bands. The RTP totals
     * are unaffected either way, since they only ever sum.
     */
    private static final class Aggregator {
        private long rounds;
        private long hits;
        private BigDecimal totalBet = BigDecimal.ZERO;
        private BigDecimal totalWin = BigDecimal.ZERO;
        private double maxWinMultiplier;
        private final long[] bands = new long[WinBandScale.BAND_COUNT];

        /** Records one complete round: its stake and the total it paid across base game and features. */
        void record(BigDecimal bet, BigDecimal win) {
            rounds++;
            totalBet = totalBet.add(bet);
            totalWin = totalWin.add(win);
            double multiplier = bet.signum() == 0 ? 0.0 : win.doubleValue() / bet.doubleValue();
            if (multiplier > 0.0) {
                hits++;
            }
            if (multiplier > maxWinMultiplier) {
                maxWinMultiplier = multiplier;
            }
            bands[WinBandScale.bandOf(multiplier)]++;
        }

        void merge(Aggregator other) {
            rounds += other.rounds;
            hits += other.hits;
            totalBet = totalBet.add(other.totalBet);
            totalWin = totalWin.add(other.totalWin);
            maxWinMultiplier = Math.max(maxWinMultiplier, other.maxWinMultiplier);
            for (int i = 0; i < bands.length; i++) {
                bands[i] += other.bands[i];
            }
        }

        BigDecimal totalBet() { return totalBet; }
        BigDecimal totalWin() { return totalWin; }
        long rounds() { return rounds; }

        RtpReport.Channel snapshot() {
            BigDecimal rtp = totalBet.signum() == 0 ? BigDecimal.ZERO
                    : totalWin.multiply(HUNDRED).divide(totalBet, 4, RoundingMode.HALF_UP);
            BigDecimal hitFrequency = rounds == 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(hits).multiply(HUNDRED)
                    .divide(BigDecimal.valueOf(rounds), 4, RoundingMode.HALF_UP);
            return RtpReport.Channel.builder()
                    .spins(rounds)
                    .totalBet(totalBet)
                    .totalWin(totalWin)
                    .rtpPercent(rtp)
                    .hits(hits)
                    .hitFrequencyPercent(hitFrequency)
                    .maxWinMultiplier(BigDecimal.valueOf(maxWinMultiplier).setScale(4, RoundingMode.HALF_UP))
                    .winDistribution(WinBandScale.toBands(bands, rounds))
                    .build();
        }
    }
}
