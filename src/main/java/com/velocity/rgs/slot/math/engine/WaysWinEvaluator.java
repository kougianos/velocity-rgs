package com.velocity.rgs.slot.math.engine;

import com.velocity.rgs.slot.math.config.SlotMathDefinition;
import com.velocity.rgs.slot.math.domain.Symbol;
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

        List<WinLine> wins = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Symbol symbol : math.symbols()) {
            // Scatters pay via ScatterTriggers, not here. Wilds only substitute (see class javadoc).
            if (symbol.isScatter() || symbol.isWild()) {
                continue;
            }
            WinLine win = evaluateSymbol(symbol, matrix, rows, cols, bySymbolId, wayBet, math);
            if (win != null) {
                wins.add(win);
                total = total.add(win.payout());
            }
        }

        wins.sort(Comparator.comparing(WinLine::payout).reversed().thenComparing(WinLine::symbolId));
        return EvaluationSupport.capped(total, wins, bet, math);
    }

    private WinLine evaluateSymbol(Symbol symbol, int[][] matrix, int rows, int cols,
                                   Map<Integer, Symbol> bySymbolId, BigDecimal wayBet,
                                   SlotMathDefinition math) {
        long ways = 1;
        int reels = 0;
        for (int col = 0; col < cols; col++) {
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
        return WinLine.ways(symbol.id(), reels, Math.toIntExact(ways), payout);
    }
}
