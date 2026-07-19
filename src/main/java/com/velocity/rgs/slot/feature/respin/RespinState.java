package com.velocity.rgs.slot.feature.respin;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The live state of a Hold &amp; Spin feature: which cells are holding a coin, what each is worth, and
 * how many respins are left before the feature settles.
 *
 * <p>Immutable - each respin produces a new instance. That is what makes the accumulation safe to keep
 * in {@code game_session.active_feature_payload}: the payload is a snapshot of a value, not a handle on
 * something still being mutated, so a session rehydrated mid-feature is byte-identical to the one that
 * was persisted.
 *
 * @param remainingRespins respins left; the feature settles at 0
 * @param coins            every coin held so far, in the order it landed
 * @param jackpotTier      the tier earned, resolved only at settlement; {@code null} while active
 */
public record RespinState(
        int remainingRespins,
        List<Coin> coins,
        String jackpotTier,
        boolean completed
) {

    public RespinState {
        coins = coins == null ? List.of() : List.copyOf(coins);
        if (remainingRespins < 0) {
            throw new IllegalArgumentException("remainingRespins must be >= 0, found " + remainingRespins);
        }
    }

    /** One locked cell and what it pays, in bet multiples. */
    public record Coin(int row, int col, BigDecimal value) {
        public Coin {
            Objects.requireNonNull(value, "coin.value");
            if (row < 0 || col < 0) {
                throw new IllegalArgumentException("coin position must be non-negative");
            }
        }
    }

    /** A fresh feature holding the coins that triggered it. */
    public static RespinState opening(List<Coin> triggerCoins, int respinsAwarded) {
        return new RespinState(respinsAwarded, triggerCoins, null, false);
    }

    public int coinCount() {
        return coins.size();
    }

    /** True when every cell of a {@code rows x cols} grid is holding a coin. */
    public boolean gridFull(int rows, int cols) {
        return coins.size() >= rows * cols;
    }

    /** Which cells are locked, as a mask the grid engine can re-draw around. */
    public boolean[][] heldMask(int rows, int cols) {
        boolean[][] held = new boolean[rows][cols];
        for (Coin coin : coins) {
            if (coin.row() < rows && coin.col() < cols) {
                held[coin.row()][coin.col()] = true;
            }
        }
        return held;
    }

    /** The sum of every held coin, in bet multiples. */
    public BigDecimal coinTotal() {
        BigDecimal total = BigDecimal.ZERO;
        for (Coin coin : coins) {
            total = total.add(coin.value());
        }
        return total;
    }

    /** This state plus {@code landed}, with the counter set per the reset-on-catch rule. */
    public RespinState withNewCoins(List<Coin> landed, int respinsAwarded) {
        if (landed.isEmpty()) {
            return new RespinState(remainingRespins - 1, coins, jackpotTier, completed);
        }
        List<Coin> merged = new ArrayList<>(coins);
        merged.addAll(landed);
        // Catching anything refills the counter - that is the mechanic's entire tension.
        return new RespinState(respinsAwarded, merged, jackpotTier, completed);
    }

    public RespinState settled(String tier) {
        return new RespinState(0, coins, tier, true);
    }
}
