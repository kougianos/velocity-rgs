package com.velocity.rgs.slot.math.engine;

import com.velocity.rgs.slot.math.config.SlotMathDefinition;
import com.velocity.rgs.slot.math.domain.Symbol;
import com.velocity.rgs.slot.math.domain.WaysDirection;
import com.velocity.rgs.slot.math.domain.WinModel;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Ways-to-win evaluator: every left-to-right path through the grid is live, so a 3-row x 5-reel grid has
 * 3^5 = 243 ways.
 *
 * <h2>The model</h2>
 *
 * For each symbol, count its hits on reel 0, reel 1, ... until a reel has none. If the run spans at least
 * three reels, it wins {@code ways = product of those per-reel counts}. Reels beyond the run are
 * irrelevant - a three-reel run is worth {@code c0 * c1 * c2} ways no matter what follows it.
 *
 * <p>The stake is split across every way ({@code wayBet = bet / rows^cols}) and a win pays
 * {@code wayBet * coefficient * ways}. That mirrors how {@link PaylineWinEvaluator} splits across lines,
 * which keeps a ways pay table in the same units as a payline one and makes the two directly comparable
 * when calibrating.
 *
 * <h2>Wilds substitute only - they do not pay on their own</h2>
 *
 * Wilds count toward every STANDARD symbol's per-reel tally, so a wild-rich screen can pay several
 * symbols at once. That is not a defect - it is the point of a ways game, and it is where the big wins
 * come from.
 *
 * <p>The one genuinely ambiguous case is a path made <em>entirely</em> of wilds. A wild belongs to every
 * symbol's run simultaneously, so "wild substitutes for everything" and "wild pays as itself" cannot both
 * hold without deciding what that path is worth: it would otherwise pay once per substituted symbol
 * <em>and</em> again as wild. Resolving it exactly needs per-way inclusion-exclusion between the wild's
 * own run and each substituted run.
 *
 * <p>Two config rules, enforced by {@link SlotMathDefinition}, sidestep that instead:
 * <ol>
 *   <li>wilds get no pay table entries under WAYS - they only ever substitute; and</li>
 *   <li>wilds may not appear on reel 0, so no run can be anchored by a wild.</li>
 * </ol>
 * Rule 2 is what actually makes an all-wild run impossible (runs start at reel 0), and it is the common
 * convention in real ways games; rule 1 then rejects wild pay table entries as the dead config they would
 * be, rather than ignoring them silently. Both fail loudly at load.
 *
 * <p>({@link PaylineWinEvaluator} has no such issue: a line pays once, for the better of its wild run and
 * its substituted run, so wilds there keep their own pay table - as all three shipped games do.)
 */
@Component
public class WaysWinEvaluator implements WinEvaluator {

    private static final int MIN_RUN = 3;

    @Override
    public WinModel model() {
        return WinModel.WAYS;
    }

    @Override
    public EvaluationResult evaluate(int[][] matrix, BigDecimal bet, SlotMathDefinition math) {
        EvaluationSupport.validateMatrix(matrix, bet, math);
        if (!math.paylines().isEmpty()) {
            throw new IllegalStateException(
                    "game " + math.gameId() + " uses WAYS but declares paylines; ways paths are implied by the grid");
        }

        int rows = math.grid().rows();
        int cols = math.grid().cols();
        Map<Integer, Symbol> bySymbolId = EvaluationSupport.indexSymbols(math.symbols());

        BigDecimal wayBet = bet.divide(BigDecimal.valueOf(rows).pow(cols), 12, RoundingMode.HALF_UP);

        boolean bothWays = math.waysDirection() == WaysDirection.BOTH_WAYS;

        List<WinLine> wins = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Symbol symbol : math.symbols()) {
            // Scatters pay via ScatterTriggers, not here. Wilds only substitute (see class javadoc).
            if (symbol.isScatter() || symbol.isWild()) {
                continue;
            }
            WinLine win = evaluateSymbol(symbol, matrix, rows, cols, bySymbolId, wayBet, math, false);
            if (win != null) {
                wins.add(win);
                total = total.add(win.payout());
            }
            // Win-both-ways: the mirrored run is a separate win, exactly as it is in the games that
            // advertise the feature - a symbol filling reels 0-2 and 3-4 pays twice on one screen. The
            // two runs are evaluated independently; no attempt is made to net off the reels they share,
            // because paying both in full is the mechanic rather than a double count.
            if (bothWays) {
                WinLine mirrored = evaluateSymbol(symbol, matrix, rows, cols, bySymbolId, wayBet, math, true);
                if (mirrored != null) {
                    wins.add(mirrored);
                    total = total.add(mirrored.payout());
                }
            }
        }

        wins.sort(Comparator.comparing(WinLine::payout).reversed().thenComparing(WinLine::symbolId));
        return EvaluationSupport.capped(total, wins, bet, math, matrix);
    }

    /**
     * A ways win names a symbol and a reel count rather than a path, so its footprint is every cell on
     * the leftmost {@code count} reels holding that symbol - or a wild standing in for it. That is the
     * same set the per-reel tally counted when it computed {@code ways}, re-read off the grid.
     */
    @Override
    public boolean[][] winningMask(int[][] matrix, List<WinLine> wins, SlotMathDefinition math) {
        int rows = math.grid().rows();
        int cols = math.grid().cols();
        boolean[][] mask = new boolean[rows][cols];
        if (wins.isEmpty()) {
            return mask;
        }
        Map<Integer, Symbol> bySymbolId = EvaluationSupport.indexSymbols(math.symbols());
        for (WinLine w : wins) {
            Symbol won = bySymbolId.get(w.symbolId());
            if (won == null) {
                continue;
            }
            // A mirrored win-both-ways run covers the rightmost reels, not the leftmost.
            for (int step = 0; step < w.count() && step < cols; step++) {
                int col = w.isRightToLeft() ? cols - 1 - step : step;
                for (int row = 0; row < rows; row++) {
                    Symbol cell = bySymbolId.get(matrix[row][col]);
                    if (cell != null && (cell.id() == won.id() || cell.substitutesFor(won.type()))) {
                        mask[row][col] = true;
                    }
                }
            }
        }
        return mask;
    }

    /**
     * One symbol's run. {@code rightToLeft} walks the reels from the rightmost inwards instead of from
     * reel 0 outwards - the mirrored half of {@link WaysDirection#BOTH_WAYS}. Everything else about the
     * count is identical, which is the point: both directions pay by the same rules.
     */
    private WinLine evaluateSymbol(Symbol symbol, int[][] matrix, int rows, int cols,
                                   Map<Integer, Symbol> bySymbolId, BigDecimal wayBet,
                                   SlotMathDefinition math, boolean rightToLeft) {
        long ways = 1;
        int reels = 0;
        for (int step = 0; step < cols; step++) {
            int col = rightToLeft ? cols - 1 - step : step;
            int hits = 0;
            for (int row = 0; row < rows; row++) {
                Symbol cell = bySymbolId.get(matrix[row][col]);
                if (cell == null) {
                    throw new IllegalStateException("unknown symbol id on grid: " + matrix[row][col]);
                }
                if (cell.id() == symbol.id() || cell.substitutesFor(symbol.type())) {
                    hits++;
                }
            }
            if (hits == 0) {
                break;
            }
            ways *= hits;
            reels++;
        }
        if (reels < MIN_RUN) {
            return null;
        }

        BigDecimal coefficient = math.payTable().lookup(symbol.id(), reels).orElse(null);
        if (coefficient == null || coefficient.signum() <= 0) {
            return null;
        }
        // Round once, at the end. With 243 ways a 1.00 stake makes wayBet ~0.0041, so rounding any
        // intermediate product to currency scale would shed a large fraction of the win.
        BigDecimal payout = wayBet.multiply(coefficient).multiply(BigDecimal.valueOf(ways))
                .setScale(2, RoundingMode.HALF_UP);
        if (payout.signum() <= 0) {
            return null;
        }
        return rightToLeft
                ? WinLine.waysRightToLeft(symbol.id(), reels, Math.toIntExact(ways), payout)
                : WinLine.ways(symbol.id(), reels, Math.toIntExact(ways), payout);
    }
}
