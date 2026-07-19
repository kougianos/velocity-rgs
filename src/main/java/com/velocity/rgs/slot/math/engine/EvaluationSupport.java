package com.velocity.rgs.slot.math.engine;

import com.velocity.rgs.slot.math.config.SlotMathDefinition;
import com.velocity.rgs.slot.math.domain.Symbol;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Plumbing shared by the {@link WinEvaluator} implementations: grid checks, symbol lookup, win cap. */
final class EvaluationSupport {

    static final String REASON_MAX_WIN_CAPPED = "MAX_WIN_CAPPED";

    private EvaluationSupport() {
    }

    static void validateMatrix(int[][] matrix, BigDecimal bet, SlotMathDefinition math) {
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
    }

    static Map<Integer, Symbol> indexSymbols(List<Symbol> symbols) {
        Map<Integer, Symbol> map = new HashMap<>(symbols.size() * 2);
        for (Symbol s : symbols) {
            map.put(s.id(), s);
        }
        return map;
    }

    static int wildId(SlotMathDefinition math) {
        return math.symbols().stream()
                .filter(Symbol::isWild)
                .findFirst()
                .map(Symbol::id)
                .orElseThrow(() -> new IllegalStateException("math has no WILD symbol"));
    }

    /**
     * Applies the per-round win cap, rounds to currency scale, and wraps the outcome as the round's
     * single drop.
     *
     * <p>{@code stopPositions} is not available at this level - an evaluator is handed a grid, not the
     * draws that produced it - so the step records an empty array. The two callers that care
     * ({@code SlotEngineService} for persistence and {@link CascadeEngine} for the sequence) hold the
     * real stops and stitch them in; nothing reads the placeholder.
     */
    static EvaluationResult capped(BigDecimal total, List<WinLine> wins, BigDecimal bet,
                                   SlotMathDefinition math, int[][] matrix) {
        List<String> reasons = new ArrayList<>();
        BigDecimal cap = bet.multiply(BigDecimal.valueOf(math.limits().maxWinPerRoundMultiplier()));
        if (total.compareTo(cap) > 0) {
            total = cap;
            reasons.add(REASON_MAX_WIN_CAPPED);
        }
        BigDecimal scaled = total.setScale(2, RoundingMode.HALF_UP);
        CascadeStep step = new CascadeStep(0, matrix, NO_STOPS, wins, BigDecimal.ONE, scaled, NO_POSITIONS);
        return new EvaluationResult(scaled, wins, reasons, List.of(step));
    }

    private static final int[] NO_STOPS = new int[0];
    private static final int[][] NO_POSITIONS = new int[0][];
}
