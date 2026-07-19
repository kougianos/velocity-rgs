package com.velocity.rgs.slot.math.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Hold &amp; Spin respins. Landing {@code triggerMinCount} coin symbols in the base game locks them in
 * place and awards {@code respinsAwarded} respins; only the cells that are <em>not</em> holding a coin
 * are re-drawn. Every new coin locks too and <strong>resets the counter</strong> back to
 * {@code respinsAwarded}, so the feature runs until the grid goes {@code respinsAwarded} spins without
 * adding anything - or until it fills completely, which pays the top jackpot.
 *
 * <p>The {@code math.respins} block of {@code games/<gameId>/<mathVersion>.json}. Absent means
 * {@link #disabled()}.
 *
 * @param coinSymbolId    which symbol id is the coin. Referenced rather than typed so a game opts in
 *                        purely through config; the symbol must exist and appear on the reel strips
 * @param triggerMinCount coins on the opening grid needed to enter the feature
 * @param respinsAwarded  respins granted on entry, and the value the counter resets to on every catch
 * @param coinValues      weighted ladder a landing coin draws its value from, in bet multiples
 * @param jackpots        tiers keyed by how many coins were held at settlement; the highest tier whose
 *                        {@code minCoins} is met is awarded. A tier at {@code rows * cols} is the
 *                        full-grid jackpot
 */
public record RespinConfig(
        boolean enabled,
        int coinSymbolId,
        int triggerMinCount,
        int respinsAwarded,
        List<CoinValueWeight> coinValues,
        List<RespinJackpot> jackpots
) {

    /** A game with no Hold &amp; Spin feature. */
    public static RespinConfig disabled() {
        return new RespinConfig(false, -1, 0, 0, List.of(), List.of());
    }

    public RespinConfig {
        coinValues = coinValues == null ? List.of() : List.copyOf(coinValues);
        jackpots = jackpots == null ? List.of() : List.copyOf(jackpots);
        if (enabled) {
            if (triggerMinCount < 2) {
                throw new IllegalArgumentException(
                        "respins.triggerMinCount must be >= 2, found " + triggerMinCount);
            }
            if (respinsAwarded < 1) {
                throw new IllegalArgumentException(
                        "respins.respinsAwarded must be >= 1, found " + respinsAwarded);
            }
            if (coinValues.isEmpty()) {
                throw new IllegalArgumentException("respins.coinValues must not be empty");
            }
            // Tiers are compared by minCoins, so a stable descending order lets the award be a
            // first-match scan rather than a sort at every settlement.
            List<RespinJackpot> sorted = new ArrayList<>(jackpots);
            sorted.sort((a, b) -> Integer.compare(b.minCoins(), a.minCoins()));
            jackpots = List.copyOf(sorted);
        }
    }

    /** Total weight of the coin-value ladder, used to size the RNG draw. */
    public int totalCoinWeight() {
        int total = 0;
        for (CoinValueWeight w : coinValues) {
            total += w.weight();
        }
        return total;
    }

    /**
     * The best jackpot tier earned by {@code coinCount}, or {@code null} if none. Tiers are held in
     * descending {@code minCoins} order, so the first match is the highest one earned.
     */
    public RespinJackpot jackpotFor(int coinCount) {
        for (RespinJackpot jackpot : jackpots) {
            if (coinCount >= jackpot.minCoins()) {
                return jackpot;
            }
        }
        return null;
    }

    /** Validates that the referenced coin symbol actually exists in the game's symbol set. */
    public void requireCoinSymbol(List<Integer> symbolIds) {
        Objects.requireNonNull(symbolIds, "symbolIds");
        if (enabled && !symbolIds.contains(coinSymbolId)) {
            throw new IllegalArgumentException(
                    "respins.coinSymbolId " + coinSymbolId + " is not a declared symbol");
        }
    }
}
