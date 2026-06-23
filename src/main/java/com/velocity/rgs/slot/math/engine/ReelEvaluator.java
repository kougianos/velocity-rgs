package com.velocity.rgs.slot.math.engine;

import com.velocity.rgs.slot.math.config.SlotMathDefinition;
import com.velocity.rgs.slot.math.domain.Payline;
import com.velocity.rgs.slot.math.domain.PayTable;
import com.velocity.rgs.slot.math.domain.Symbol;
import com.velocity.rgs.slot.math.domain.SymbolType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Stateless left-to-right payline evaluator (A.4 / Milestone 1, Task 1.3).
 *
 * <p>Rules:
 * <ul>
 *   <li>Each payline reads symbols from the matrix in order. A run is counted from the leftmost reel.</li>
 *   <li>The stake is split evenly across all active paylines: {@code lineBet = bet / paylines}. A line
 *       payout is {@code lineBet * payTableCoefficient}, matching the conventional fixed-payline model.</li>
 *   <li>{@link SymbolType#WILD} substitutes for {@link SymbolType#STANDARD} only (never for SCATTER).</li>
 *   <li>{@link SymbolType#SCATTER} occurrences break a line run; scatters do not contribute to line payouts.</li>
 *   <li>Where leading wilds also pay on their own (≥ 3), the higher payout is chosen.</li>
 *   <li>The aggregate is capped by {@code bet * limits.maxWinPerRoundMultiplier}; {@code MAX_WIN_CAPPED}
 *       is emitted as a reason code when truncation occurs.</li>
 * </ul>
 */
@Component
public class ReelEvaluator {

    private static final String REASON_MAX_WIN_CAPPED = "MAX_WIN_CAPPED";

    public EvaluationResult evaluate(int[][] matrix, BigDecimal bet, SlotMathDefinition math) {
        if (matrix == null) {
            throw new IllegalArgumentException("matrix is required");
        }
        if (bet == null || bet.signum() <= 0) {
            throw new IllegalArgumentException("bet must be > 0");
        }
        if (matrix.length != math.grid().rows()) {
            throw new IllegalArgumentException(
                    "matrix has " + matrix.length + " rows; expected " + math.grid().rows());
        }
        for (int[] row : matrix) {
            if (row.length != math.grid().cols()) {
                throw new IllegalArgumentException(
                        "matrix row has " + row.length + " cols; expected " + math.grid().cols());
            }
        }

        Map<Integer, Symbol> bySymbolId = indexSymbols(math.symbols());
        int wildId = math.symbols().stream()
                .filter(Symbol::isWild)
                .findFirst()
                .map(Symbol::id)
                .orElseThrow(() -> new IllegalStateException("math has no WILD symbol"));

        List<WinLine> wins = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal lineBet = bet.divide(BigDecimal.valueOf(math.paylines().size()), 12, RoundingMode.HALF_UP);
        for (Payline payline : math.paylines()) {
            int[] line = readLine(matrix, payline.coords());
            evaluateLine(payline.id(), line, lineBet, math.payTable(), bySymbolId, wildId)
                    .ifPresent(w -> wins.add(w));
        }
        for (WinLine w : wins) {
            total = total.add(w.payout());
        }

        List<String> reasons = new ArrayList<>();
        BigDecimal cap = bet.multiply(BigDecimal.valueOf(math.limits().maxWinPerRoundMultiplier()));
        if (total.compareTo(cap) > 0) {
            total = cap;
            reasons.add(REASON_MAX_WIN_CAPPED);
        }
        total = total.setScale(2, RoundingMode.HALF_UP);
        return new EvaluationResult(total, wins, reasons);
    }

    private Map<Integer, Symbol> indexSymbols(List<Symbol> symbols) {
        Map<Integer, Symbol> map = new HashMap<>(symbols.size() * 2);
        for (Symbol s : symbols) {
            map.put(s.id(), s);
        }
        return map;
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
                .map(p -> new WinLine(paylineId, symbolId, count, p));
    }
}
