package com.velocity.rgs.slot.math.engine;

import com.velocity.rgs.slot.math.config.SlotMathDefinition;
import com.velocity.rgs.slot.math.config.WildFeatureConfig;
import com.velocity.rgs.slot.math.domain.ReelStripSet;
import com.velocity.rgs.slot.math.domain.Symbol;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Applies a game's configured wild behaviours to a freshly drawn grid, before anything evaluates it.
 *
 * <p>Three behaviours, all optional and all independent of the win model:
 * <ul>
 *   <li><b>expanding</b> - a wild fills its whole reel;</li>
 *   <li><b>sticky</b> - wilds carried in from previous spins are stamped back onto the board;</li>
 *   <li><b>walking</b> - each carried wild first shifts one reel left, and drops off the board when it
 *       walks past reel 0.</li>
 * </ul>
 *
 * <p>Stateless: the carried wilds come in and the surviving ones go out, so the caller decides where
 * they live between spins ({@code active_feature_payload}, for the free-spins loop). Nothing here draws
 * randomness - these are deterministic transforms of an already-drawn grid, which is what keeps them out
 * of the replay contract entirely.
 */
@Component
public class WildFeatureEngine {

    public static final String REASON_EXPANDED = "WILD_EXPANDED";
    public static final String REASON_STICKY = "WILD_STICKY_CARRIED";
    public static final String REASON_WALKED = "WILD_WALKED";

    /**
     * Rewrites {@code matrix} in line with the game's wild config.
     *
     * @param carried wild cells held over from previous spins of the same feature; empty on the first
     * @return the transformed grid plus the wilds to carry into the next spin
     */
    public WildOutcome apply(int[][] matrix, SlotMathDefinition math, ReelStripSet stripSet,
                             List<WildCell> carried) {
        WildFeatureConfig config = math.wildFeatures();
        if (!config.appliesOn(stripSet)) {
            return new WildOutcome(matrix, List.of(), List.of(), List.of());
        }

        int rows = math.grid().rows();
        int cols = math.grid().cols();
        int wildId = EvaluationSupport.wildId(math);
        List<String> reasons = new ArrayList<>();

        int[][] grid = copyOf(matrix, rows, cols);

        // 1. Carried wilds land first - walking ones a reel to the left of where they were.
        Set<WildCell> live = new LinkedHashSet<>();
        for (WildCell wild : carried) {
            WildCell moved = config.walking() ? wild.steppedLeft() : wild;
            if (moved.col() < 0 || moved.col() >= cols || moved.row() < 0 || moved.row() >= rows) {
                continue;   // walked off the board
            }
            grid[moved.row()][moved.col()] = wildId;
            live.add(moved.withOneFewerSpin());
        }
        if (!carried.isEmpty()) {
            reasons.add(config.walking() ? REASON_WALKED : REASON_STICKY);
        }

        // 2. Wilds now on the board (drawn or carried) expand down their reel.
        if (config.expanding()) {
            boolean expandedAny = false;
            for (int c = 0; c < cols; c++) {
                if (!columnHoldsWild(grid, rows, c, wildId)) {
                    continue;
                }
                for (int r = 0; r < rows; r++) {
                    grid[r][c] = wildId;
                }
                expandedAny = true;
            }
            if (expandedAny) {
                reasons.add(REASON_EXPANDED);
            }
        }

        // 3. Decide what survives into the next spin. Only wilds the player can still see persist, and
        //    only while they have spins left - an expanded column would otherwise stick the whole reel
        //    forever, which is not the mechanic.
        List<WildCell> next = new ArrayList<>();
        if (config.sticky()) {
            for (WildCell wild : live) {
                if (wild.remainingSpins() > 0) {
                    next.add(wild);
                }
            }
            for (int c = 0; c < cols; c++) {
                for (int r = 0; r < rows; r++) {
                    if (matrix[r][c] == wildId) {
                        next.add(new WildCell(r, c, config.stickySpins()));
                    }
                }
            }
        }
        return new WildOutcome(grid, next, List.copyOf(live), reasons);
    }

    private static boolean columnHoldsWild(int[][] grid, int rows, int col, int wildId) {
        for (int r = 0; r < rows; r++) {
            if (grid[r][col] == wildId) {
                return true;
            }
        }
        return false;
    }

    private static int[][] copyOf(int[][] matrix, int rows, int cols) {
        int[][] copy = new int[rows][cols];
        for (int r = 0; r < rows; r++) {
            System.arraycopy(matrix[r], 0, copy[r], 0, cols);
        }
        return copy;
    }

    /**
     * A wild that persists between spins.
     *
     * @param remainingSpins how many further spins it survives; 0 means it drops after this one
     */
    public record WildCell(int row, int col, int remainingSpins) {

        public WildCell steppedLeft() {
            return new WildCell(row, col - 1, remainingSpins);
        }

        public WildCell withOneFewerSpin() {
            return new WildCell(row, col, remainingSpins - 1);
        }
    }

    /**
     * The transformed grid, the wilds to carry forward, and what happened.
     *
     * @param landedCarry where the incoming carry ended up <em>on this board</em> - already stepped left
     *                    for a walking wild, and with anything that walked off the grid dropped. Distinct
     *                    from {@code carryForward}, which is next spin's input: this is the set of cells
     *                    the player did not draw, and the only way a surface downstream can tell a carried
     *                    wild apart from a drawn one after the transform has flattened both into the grid
     */
    public record WildOutcome(int[][] matrix, List<WildCell> carryForward, List<WildCell> landedCarry,
                              List<String> reasonCodes) {
        public WildOutcome {
            carryForward = carryForward == null ? List.of() : List.copyOf(carryForward);
            landedCarry = landedCarry == null ? List.of() : List.copyOf(landedCarry);
            reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
        }
    }

    /** Whether a symbol id is the game's wild - exposed so callers can seed carried wilds. */
    public static boolean isWild(SlotMathDefinition math, int symbolId) {
        for (Symbol symbol : math.symbols()) {
            if (symbol.id() == symbolId) {
                return symbol.isWild();
            }
        }
        return false;
    }
}
