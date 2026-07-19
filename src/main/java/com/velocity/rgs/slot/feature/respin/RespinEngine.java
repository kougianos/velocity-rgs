package com.velocity.rgs.slot.feature.respin;

import com.velocity.rgs.common.money.Money;
import com.velocity.rgs.rng.RandomNumberGenerator;
import com.velocity.rgs.slot.math.config.CoinValueWeight;
import com.velocity.rgs.slot.math.config.RespinConfig;
import com.velocity.rgs.slot.math.config.RespinJackpot;
import com.velocity.rgs.slot.math.config.SlotMathDefinition;
import com.velocity.rgs.slot.math.domain.ReelStripSet;
import com.velocity.rgs.slot.math.engine.GridGenerationEngine;
import com.velocity.rgs.slot.math.engine.GridGenerationResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The Hold &amp; Spin feature: coins lock, everything else respins, and the counter refills every time
 * a new coin is caught.
 *
 * <p>Stateless - {@link RespinState} is passed in and a new one comes back, which is what lets the
 * whole feature live in {@code game_session.active_feature_payload} between HTTP calls. All randomness
 * flows through the supplied RNG (the grid respin <em>and</em> each landing coin's value), so the round
 * is captured by its draw log exactly like a base spin.
 *
 * <h2>Reasons the feature ends</h2>
 * <ul>
 *   <li>the counter reaches zero - {@code respinsAwarded} respins in a row caught nothing; or</li>
 *   <li>the grid fills completely, which ends it immediately and pays the top jackpot tier.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class RespinEngine {

    public static final String REASON_TRIGGERED = "RESPIN_TRIGGERED";
    public static final String REASON_COUNTER_RESET = "RESPIN_COUNTER_RESET";
    public static final String REASON_GRID_FULL = "RESPIN_GRID_FULL";
    public static final String REASON_SETTLED = "RESPIN_SETTLED";
    public static final String REASON_JACKPOT_PREFIX = "RESPIN_JACKPOT_";

    private final GridGenerationEngine gridEngine;

    /** Coins on the grid, used both to test the trigger and to seed the opening state. */
    public int countCoins(int[][] matrix, RespinConfig config) {
        if (!config.enabled()) {
            return 0;
        }
        int count = 0;
        for (int[] row : matrix) {
            for (int symbol : row) {
                if (symbol == config.coinSymbolId()) {
                    count++;
                }
            }
        }
        return count;
    }

    public boolean triggers(int[][] matrix, RespinConfig config) {
        return config.enabled() && countCoins(matrix, config) >= config.triggerMinCount();
    }

    /**
     * Locks the coins that triggered the feature and draws a value for each. Values are drawn in
     * reading order (top-down within a column, columns left to right) so the sequence is reproducible.
     */
    public RespinState start(int[][] matrix, RespinConfig config, RandomNumberGenerator rng) {
        List<RespinState.Coin> coins = collectCoins(matrix, config, rng, new boolean[0][0]);
        return RespinState.opening(coins, config.respinsAwarded());
    }

    /**
     * Opens a <em>bought</em> feature: {@code coinCount} coins locked on distinct random cells, each
     * with a drawn value, and a full respin counter.
     *
     * <p>A purchase has no triggering grid to read coins off, so they are placed directly rather than
     * by re-spinning the reels until enough land - which at a ~1-in-580 trigger rate would be both slow
     * and a different distribution from the one the buy is priced against. Positions are drawn by
     * rejection over the grid's cells, so they are uniform and distinct; both the positions and the
     * values come from the round's RNG, so a bought feature replays exactly like a triggered one.
     */
    public RespinState startBought(SlotMathDefinition math, int coinCount, RandomNumberGenerator rng) {
        RespinConfig config = math.respins();
        int rows = math.grid().rows();
        int cols = math.grid().cols();
        int cells = rows * cols;
        if (coinCount < 1 || coinCount > cells) {
            throw new IllegalArgumentException(
                    "bought coin count must be within 1.." + cells + ", found " + coinCount);
        }

        boolean[][] taken = new boolean[rows][cols];
        List<RespinState.Coin> coins = new ArrayList<>(coinCount);
        while (coins.size() < coinCount) {
            int cell = rng.nextIndex(cells);
            int row = cell / cols;
            int col = cell % cols;
            if (taken[row][col]) {
                continue;
            }
            taken[row][col] = true;
            coins.add(new RespinState.Coin(row, col, drawCoinValue(config, rng)));
        }
        // Reading order, so the board renders and persists identically to a triggered feature.
        coins.sort(Comparator.comparingInt(RespinState.Coin::col)
                .thenComparingInt(RespinState.Coin::row));
        return RespinState.opening(coins, config.respinsAwarded());
    }

    /**
     * Plays one respin: re-draw the unlocked cells, lock whatever coins land, and refill the counter if
     * any did. A full grid ends the feature on the spot.
     */
    public RespinOutcome respin(RespinState state, SlotMathDefinition math, ReelStripSet stripSet,
                                RandomNumberGenerator rng) {
        RespinConfig config = math.respins();
        int rows = math.grid().rows();
        int cols = math.grid().cols();

        boolean[][] held = state.heldMask(rows, cols);
        GridGenerationResult grid = gridEngine.respin(math, stripSet, rng, held, config.coinSymbolId());
        List<RespinState.Coin> landed = collectCoins(grid.matrix(), config, rng, held);

        RespinState next = state.withNewCoins(landed, config.respinsAwarded());
        List<String> reasons = new ArrayList<>();
        if (!landed.isEmpty()) {
            reasons.add(REASON_COUNTER_RESET);
        }

        boolean gridFull = next.gridFull(rows, cols);
        if (gridFull) {
            reasons.add(REASON_GRID_FULL);
        }
        boolean finished = gridFull || next.remainingRespins() <= 0;
        return new RespinOutcome(next, grid.matrix(), landed.size(), !landed.isEmpty(), gridFull,
                finished, reasons);
    }

    /**
     * Settles the feature: every coin pays its value, plus the highest jackpot tier the final coin
     * count earned. The full-grid tier is simply the one whose {@code minCoins} is the whole grid.
     */
    public Settlement settle(RespinState state, SlotMathDefinition math, BigDecimal bet, String currency) {
        RespinConfig config = math.respins();
        RespinJackpot jackpot = config.jackpotFor(state.coinCount());

        BigDecimal multiples = state.coinTotal();
        if (jackpot != null) {
            multiples = multiples.add(jackpot.multiplier());
        }
        BigDecimal raw = multiples.multiply(bet);

        // The feature is funded by the same round as the spin that triggered it, so it answers to the
        // same per-round ceiling.
        BigDecimal cap = bet.multiply(BigDecimal.valueOf(math.limits().maxWinPerRoundMultiplier()));
        List<String> reasons = new ArrayList<>();
        reasons.add(REASON_SETTLED);
        if (jackpot != null) {
            reasons.add(REASON_JACKPOT_PREFIX + jackpot.tier());
        }
        if (raw.compareTo(cap) > 0) {
            raw = cap;
            reasons.add("MAX_WIN_CAPPED");
        }

        Money win = Money.of(raw.setScale(Money.minorUnitScale(currency), RoundingMode.HALF_UP), currency);
        return new Settlement(state.settled(jackpot == null ? null : jackpot.tier()), win,
                jackpot == null ? null : jackpot.tier(), reasons);
    }

    /**
     * Every coin on {@code matrix} that is not already held, with a freshly drawn value.
     *
     * <p>Two draws per coin would be wrong here: the grid draw already decided <em>where</em> a coin
     * landed, so this only decides what it is worth. Passing an empty mask collects every coin, which
     * is what the opening grid needs.
     */
    private List<RespinState.Coin> collectCoins(int[][] matrix, RespinConfig config,
                                                RandomNumberGenerator rng, boolean[][] held) {
        List<RespinState.Coin> coins = new ArrayList<>();
        for (int c = 0; c < matrix[0].length; c++) {
            for (int r = 0; r < matrix.length; r++) {
                boolean alreadyHeld = held.length > r && held[r].length > c && held[r][c];
                if (alreadyHeld || matrix[r][c] != config.coinSymbolId()) {
                    continue;
                }
                coins.add(new RespinState.Coin(r, c, drawCoinValue(config, rng)));
            }
        }
        return coins;
    }

    /** One weighted draw from the coin-value ladder. */
    private BigDecimal drawCoinValue(RespinConfig config, RandomNumberGenerator rng) {
        int roll = rng.nextIndex(config.totalCoinWeight());
        int cursor = 0;
        for (CoinValueWeight rung : config.coinValues()) {
            cursor += rung.weight();
            if (roll < cursor) {
                return rung.value();
            }
        }
        return config.coinValues().get(config.coinValues().size() - 1).value();
    }

    /**
     * The result of one respin.
     *
     * @param counterReset whether a coin was caught, refilling the counter
     * @param gridFull     whether the board filled, ending the feature with the top jackpot
     * @param finished     whether the feature is over, for either reason
     */
    public record RespinOutcome(
            RespinState state,
            int[][] matrix,
            int newCoins,
            boolean counterReset,
            boolean gridFull,
            boolean finished,
            List<String> reasonCodes
    ) {
        public RespinOutcome {
            reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
        }
    }

    /** The settled feature: its final state, what it paid, and which tier (if any) it earned. */
    public record Settlement(RespinState state, Money win, String jackpotTier, List<String> reasonCodes) {
        public Settlement {
            reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
        }
    }
}
