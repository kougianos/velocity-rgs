package com.velocity.rgs.slot.feature.respin;

import com.velocity.rgs.slot.math.config.RespinConfig;
import com.velocity.rgs.slot.math.config.RespinJackpot;
import lombok.Builder;

import java.math.BigDecimal;
import java.util.List;

/**
 * The client-safe projection of a live Hold &amp; Spin feature: where the coins are, what they are
 * worth, how many respins are left, and which jackpot tiers are still in reach.
 *
 * <p>Everything here is already visible on the player's screen - a locked coin and its value are the
 * whole point of the mechanic. There is no hidden state to leak, unlike Pick &amp; Collect's unopened
 * tiles, which is why this is a straight projection rather than a redaction.
 */
@Builder
public record RespinFeatureView(
        int remainingRespins,
        int coinCount,
        int gridCells,
        BigDecimal coinTotal,
        List<CoinView> coins,
        List<JackpotView> jackpots,
        String awardedJackpotTier
) {

    /** One locked cell: its position on the grid and its value in bet multiples. */
    public record CoinView(int row, int col, BigDecimal value) {}

    /**
     * A jackpot tier as the client shows the ladder.
     *
     * @param reached whether the current coin count already earns this tier
     */
    public record JackpotView(String tier, int minCoins, BigDecimal multiplier, boolean reached) {}

    public static RespinFeatureView of(RespinState state, RespinConfig config, int rows, int cols) {
        List<JackpotView> tiers = config.jackpots().stream()
                .sorted((a, b) -> Integer.compare(a.minCoins(), b.minCoins()))
                .map(j -> new JackpotView(j.tier(), j.minCoins(), j.multiplier(),
                        state.coinCount() >= j.minCoins()))
                .toList();
        RespinJackpot earned = config.jackpotFor(state.coinCount());
        return RespinFeatureView.builder()
                .remainingRespins(state.remainingRespins())
                .coinCount(state.coinCount())
                .gridCells(rows * cols)
                .coinTotal(state.coinTotal())
                .coins(state.coins().stream()
                        .map(c -> new CoinView(c.row(), c.col(), c.value()))
                        .toList())
                .jackpots(tiers)
                .awardedJackpotTier(state.completed() ? state.jackpotTier()
                        : (earned == null ? null : earned.tier()))
                .build();
    }
}
