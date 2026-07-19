package com.velocity.rgs.slot.math.engine;

import com.velocity.rgs.rng.RandomNumberGenerator;
import com.velocity.rgs.slot.math.config.CascadeConfig;
import com.velocity.rgs.slot.math.config.SlotMathDefinition;
import com.velocity.rgs.slot.math.domain.ReelStripSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Drives a round's tumble: evaluate, clear what paid, refill, evaluate again - until a drop pays nothing
 * or {@code cascades.maxCascades} refills have happened. Each drop pays at its own progressive
 * multiplier from {@link CascadeConfig#multiplierFor(int)}.
 *
 * <p>The engine composes a {@link WinEvaluator} (which model the game uses) with
 * {@link GridGenerationEngine} (how a board is refilled) without either knowing about cascades. That
 * keeps the mechanic orthogonal to the win model: a cascading ways game needs no new code, only config.
 *
 * <p><strong>The single RNG passed in is used for every refill.</strong> It is the round's RNG, so all
 * refill draws land in the round's draw log in order and the whole sequence replays bit-exact from
 * {@code game_round.rng_draws}. See {@link GridGenerationEngine#refill}.
 */
@Component
@RequiredArgsConstructor
public class CascadeEngine {

    static final String REASON_CASCADED = "CASCADED";
    static final String REASON_CASCADE_LIMIT = "CASCADE_LIMIT_REACHED";

    private final GridGenerationEngine gridEngine;

    /**
     * Plays the round out from its opening board.
     *
     * @param initialGrid  the board produced by {@link GridGenerationEngine#generate}
     * @param initialStops the reel stops that produced it, recorded on step 0
     * @return the aggregate result, whose {@link EvaluationResult#steps()} is the full drop sequence
     */
    public EvaluationResult run(int[][] initialGrid, int[] initialStops, BigDecimal bet,
                                SlotMathDefinition math, ReelStripSet stripSet,
                                WinEvaluator evaluator, RandomNumberGenerator rng) {
        CascadeConfig config = math.cascades();
        List<CascadeStep> steps = new ArrayList<>();
        List<WinLine> flatWins = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;

        int[][] grid = initialGrid;
        int[] stops = initialStops;

        for (int index = 0; ; index++) {
            EvaluationResult drop = evaluator.evaluate(grid, bet, math);
            BigDecimal multiplier = config.multiplierFor(index);
            List<WinLine> scaled = scale(drop.winLines(), multiplier);
            BigDecimal stepWin = sum(scaled);

            boolean[][] cleared = drop.winLines().isEmpty()
                    ? new boolean[math.grid().rows()][math.grid().cols()]
                    : evaluator.winningMask(grid, drop.winLines(), math);
            boolean paid = !drop.winLines().isEmpty();

            // A drop that pays nothing clears nothing, so its recorded footprint is empty - and it is
            // the board the player is left looking at, which is why it is still recorded as a step.
            boolean last = !paid || index >= config.maxCascades();
            steps.add(new CascadeStep(index, grid, stops, scaled, multiplier, stepWin,
                    last ? new int[0][] : positionsOf(cleared)));
            flatWins.addAll(scaled);
            total = total.add(stepWin);
            reasons.addAll(drop.reasonCodes());

            if (last) {
                if (paid && index >= config.maxCascades()) {
                    reasons.add(REASON_CASCADE_LIMIT);
                }
                break;
            }

            GridGenerationResult refilled = gridEngine.refill(math, stripSet, rng, grid, cleared);
            grid = refilled.matrix();
            stops = refilled.stopPositions();
        }

        if (steps.size() > 1) {
            reasons.add(REASON_CASCADED);
        }
        // The per-drop cap has already bounded each step, but a chain of capped drops can still exceed
        // the round's ceiling, so the aggregate is what the limit is actually enforced against.
        BigDecimal cap = bet.multiply(BigDecimal.valueOf(math.limits().maxWinPerRoundMultiplier()));
        if (total.compareTo(cap) > 0) {
            total = cap;
            if (!reasons.contains(EvaluationSupport.REASON_MAX_WIN_CAPPED)) {
                reasons.add(EvaluationSupport.REASON_MAX_WIN_CAPPED);
            }
        }
        return new EvaluationResult(total.setScale(2, RoundingMode.HALF_UP), flatWins,
                dedupe(reasons), steps);
    }

    /**
     * Restates each win at the step's progressive multiplier, so the flattened {@code winLines} on the
     * round still sum to what the round paid and a client can render one chip per win without knowing
     * which drop it came from.
     */
    private static List<WinLine> scale(List<WinLine> wins, BigDecimal multiplier) {
        if (multiplier.compareTo(BigDecimal.ONE) == 0) {
            return wins;
        }
        List<WinLine> out = new ArrayList<>(wins.size());
        for (WinLine w : wins) {
            out.add(new WinLine(w.lineId(), w.symbolId(), w.count(), w.ways(),
                    w.payout().multiply(multiplier).setScale(2, RoundingMode.HALF_UP)));
        }
        return out;
    }

    private static BigDecimal sum(List<WinLine> wins) {
        BigDecimal total = BigDecimal.ZERO;
        for (WinLine w : wins) {
            total = total.add(w.payout());
        }
        return total;
    }

    /** The mask as {@code [row, col]} pairs, in reading order, for persistence and the client. */
    private static int[][] positionsOf(boolean[][] mask) {
        List<int[]> cells = new ArrayList<>();
        for (int r = 0; r < mask.length; r++) {
            for (int c = 0; c < mask[r].length; c++) {
                if (mask[r][c]) {
                    cells.add(new int[]{r, c});
                }
            }
        }
        return cells.toArray(new int[0][]);
    }

    /** Reason codes repeat across drops (one {@code MAX_WIN_CAPPED} per capped step); report each once. */
    private static List<String> dedupe(List<String> reasons) {
        List<String> out = new ArrayList<>(reasons.size());
        for (String r : reasons) {
            if (!out.contains(r)) {
                out.add(r);
            }
        }
        return out;
    }
}
