package com.velocity.rgs.slot.feature.freespins;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Round-trips the inputs a free-spins <em>settlement</em> needs, through the map that lands in
 * {@code game_round.feature_context}.
 *
 * <p>The last free spin of a feature does not pay what it drew. It pays the whole feature: every spin's
 * win accumulated on the session, times the boost a bonus buy attached at purchase. Both of those live
 * on {@code game_session} and neither is derivable from the round's draws, so a replay that rebuilds
 * only this spin's lines reconstructs the board perfectly and then disagrees with the money by a factor
 * of the entire feature - which is exactly what it did before this was recorded.
 *
 * <p>Written on every spin of the loop rather than only the last, so the accumulator can be read across
 * a feature and not just at its end. Only the settling round <em>needs</em> it, and only the settling
 * round is refused when it is missing.
 */
@Component
public class FreeSpinsSettlementCodec {

    /** Key under which the block rides inside {@code feature_context}. */
    public static final String KEY = "freeSpins";

    private static final String ACCUMULATED_BEFORE = "accumulatedWinBefore";
    private static final String BUY_MULTIPLIER = "buyMultiplier";
    private static final String SETTLED = "settled";

    /**
     * The feature's running state as this spin began.
     *
     * @param accumulatedWinBefore total won by earlier spins of this feature, before this one's lines
     * @param buyMultiplier        the boost a bonus buy attached to the whole feature win; {@code 1} for
     *                             an organically triggered feature, which carries no marker
     * @param settled              whether this spin ended the feature, and therefore paid all of it
     */
    public record FreeSpinsSettlement(BigDecimal accumulatedWinBefore, BigDecimal buyMultiplier,
                                      boolean settled) {

        /** What this round paid out, given the win its own lines produced. */
        public BigDecimal payoutFor(BigDecimal ownWin) {
            return accumulatedWinBefore.add(ownWin).multiply(buyMultiplier);
        }
    }

    /** The settlement inputs as a JSON-safe map. Never contains a null value, at any depth. */
    public Map<String, Object> encode(FreeSpinsSettlement settlement) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put(ACCUMULATED_BEFORE, settlement.accumulatedWinBefore().toPlainString());
        block.put(BUY_MULTIPLIER, settlement.buyMultiplier().toPlainString());
        block.put(SETTLED, settlement.settled());
        return new LinkedHashMap<>(Map.of(KEY, block));
    }

    /** Rebuilds what a previous {@link #encode} wrote, or null when the round carries no such block. */
    @SuppressWarnings("unchecked")
    public FreeSpinsSettlement decode(Map<String, Object> payload) {
        if (payload == null || !(payload.get(KEY) instanceof Map<?, ?> raw)) {
            return null;
        }
        try {
            Map<String, Object> block = (Map<String, Object>) raw;
            return new FreeSpinsSettlement(
                    new BigDecimal(block.get(ACCUMULATED_BEFORE).toString()),
                    new BigDecimal(block.get(BUY_MULTIPLIER).toString()),
                    Boolean.TRUE.equals(block.get(SETTLED)));
        } catch (RuntimeException ex) {
            // Unreadable is treated as absent, which the settlement path refuses outright rather than
            // guessing at - the alternative is claiming a payout proof built on a default.
            return null;
        }
    }
}
