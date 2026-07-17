package com.velocity.rgs.slot.math.engine;

import com.velocity.rgs.slot.math.config.SlotMathDefinition;
import com.velocity.rgs.slot.math.domain.PayTable;
import com.velocity.rgs.slot.math.domain.Payline;
import com.velocity.rgs.slot.math.domain.Symbol;
import com.velocity.rgs.slot.math.domain.WinModel;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Stateless left-to-right payline evaluator (A.4 / Milestone 1, Task 1.3) - the model every game shipped
 * so far uses. The logic is unchanged from when it lived in {@code ReelEvaluator}; only the
 * {@link WinEvaluator} interface and the shared helpers moved.
 *
 * <p>Rules:
 * <ul>
 *   <li>Each payline reads symbols from the matrix in order. A run is counted from the leftmost reel.</li>
 *   <li>The stake is split evenly across all active paylines: {@code lineBet = bet / paylines}. A line
 *       payout is {@code lineBet * payTableCoefficient}, matching the conventional fixed-payline model.</li>
 *   <li>{@link com.velocity.rgs.slot.math.domain.SymbolType#WILD} substitutes for
 *       {@link com.velocity.rgs.slot.math.domain.SymbolType#STANDARD} only (never for SCATTER).</li>
 *   <li>SCATTER occurrences break a line run; scatters do not contribute to line payouts.</li>
 *   <li>Where leading wilds also pay on their own (>= 3), the higher payout is chosen.</li>
 *   <li>The aggregate is capped by {@code bet * limits.maxWinPerRoundMultiplier}; {@code MAX_WIN_CAPPED}
 *       is emitted as a reason code when truncation occurs.</li>
 * </ul>
 *
 * <p>Rounding happens per line, as it always has. That ordering is baked into the calibrated RTP of the
 * shipped games, so do not "tidy" it into a round-once-at-the-end scheme without re-running
 * {@code mvn -Prtp test}.
 */
@Component
public class PaylineWinEvaluator implements WinEvaluator {

    @Override
    public WinModel model() {
        return WinModel.PAYLINES;
    }

    @Override
    public EvaluationResult evaluate(int[][] matrix, BigDecimal bet, SlotMathDefinition math) {
        EvaluationSupport.validateMatrix(matrix, bet, math);
        if (math.paylines().isEmpty()) {
            throw new IllegalStateException("game " + math.gameId() + " uses PAYLINES but declares no paylines");
        }

        Map<Integer, Symbol> bySymbolId = EvaluationSupport.indexSymbols(math.symbols());
        int wildId = EvaluationSupport.wildId(math);

        List<WinLine> wins = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal lineBet = bet.divide(BigDecimal.valueOf(math.paylines().size()), 12, RoundingMode.HALF_UP);
        for (Payline payline : math.paylines()) {
            int[] line = readLine(matrix, payline.coords());
            evaluateLine(payline.id(), line, lineBet, math.payTable(), bySymbolId, wildId)
                    .ifPresent(wins::add);
        }
        for (WinLine w : wins) {
            total = total.add(w.payout());
        }
        return EvaluationSupport.capped(total, wins, bet, math);
    }

    private int[] readLine(int[][] matrix, int[][] coords) {
        int[] line = new int[coords.length];
        for (int i = 0; i < coords.length; i++) {
            line[i] = matrix[coords[i][0]][coords[i][1]];
        }
        return line;
    }

    private Optional<WinLine> evaluateLine(int paylineId, int[] line, BigDecimal lineBet, PayTable payTable,
                                           Map<Integer, Symbol> bySymbolId, int wildId) {
        Symbol first = bySymbolId.get(line[0]);
        if (first == null) {
            throw new IllegalStateException("unknown symbol id on grid: " + line[0]);
        }
        if (first.isScatter()) {
            return Optional.empty();
        }

        int wildPrefix = countWildPrefix(line, bySymbolId);
        int baseSymbolId = -1;
        for (int i = wildPrefix; i < line.length; i++) {
            Symbol s = bySymbolId.get(line[i]);
            if (s.isScatter()) {
                break;
            }
            if (!s.isWild()) {
                baseSymbolId = s.id();
                break;
            }
        }

        Optional<WinLine> baseRunPayout = Optional.empty();
        if (baseSymbolId != -1) {
            Symbol baseSym = bySymbolId.get(baseSymbolId);
            int runCount = 0;
            for (int i = 0; i < line.length; i++) {
                Symbol s = bySymbolId.get(line[i]);
                boolean match = s.id() == baseSymbolId
                        || (s.isWild() && s.substitutesFor(baseSym.type()));
                if (match) {
                    runCount++;
                } else {
                    break;
                }
            }
            baseRunPayout = payoutFor(paylineId, baseSymbolId, runCount, lineBet, payTable);
        }

        Optional<WinLine> wildRunPayout = Optional.empty();
        if (wildPrefix >= 3) {
            wildRunPayout = payoutFor(paylineId, wildId, wildPrefix, lineBet, payTable);
        }

        if (baseRunPayout.isPresent() && wildRunPayout.isPresent()) {
            return wildRunPayout.get().payout().compareTo(baseRunPayout.get().payout()) > 0
                    ? wildRunPayout : baseRunPayout;
        }
        return baseRunPayout.isPresent() ? baseRunPayout : wildRunPayout;
    }

    private int countWildPrefix(int[] line, Map<Integer, Symbol> bySymbolId) {
        int n = 0;
        for (int id : line) {
            if (bySymbolId.get(id).isWild()) {
                n++;
            } else {
                break;
            }
        }
        return n;
    }

    private Optional<WinLine> payoutFor(int paylineId, int symbolId, int count, BigDecimal lineBet,
                                        PayTable payTable) {
        if (count < 3) {
            return Optional.empty();
        }
        return payTable.lookup(symbolId, count)
                .map(coef -> lineBet.multiply(coef).setScale(2, RoundingMode.HALF_UP))
                .filter(p -> p.signum() > 0)
                .map(p -> WinLine.payline(paylineId, symbolId, count, p));
    }
}
