package com.velocity.rgs.slot.feature.respin;

import com.velocity.rgs.common.error.ErrorCode;
import com.velocity.rgs.common.error.RgsException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Round-trips a {@link RespinState} through the plain {@code Map} that lands in
 * {@code game_session.active_feature_payload}.
 *
 * <p>Its own type rather than a pair of private methods on the engine service because the map has a
 * contract that is easy to break silently: it is handed to
 * {@link com.velocity.rgs.slot.fsm.SessionState.RespinAwaiting}, whose {@code Map.copyOf} rejects
 * <strong>null values outright</strong>. Writing an un-earned jackpot tier as {@code null} therefore
 * fails the spin that triggers the feature - roughly one spin in 580, which is late enough to reach
 * production and rare enough to look like a fluke. Keeping the encoder and decoder together, with a
 * test over the shape, is what makes that invariant enforceable.
 */
@Component
public class RespinPayloadCodec {

    private static final String REMAINING_RESPINS = "remainingRespins";
    private static final String COINS = "coins";
    private static final String JACKPOT_TIER = "jackpotTier";
    private static final String COMPLETED = "completed";

    /**
     * The feature's whole accumulation: every locked coin with its position and value, plus the respin
     * counter. Both are the state - lose either and the feature cannot be resumed - so both are written
     * on every transition rather than recomputed from the last grid.
     *
     * <p>Never contains a null value, at any depth.
     */
    public Map<String, Object> encode(RespinState state) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(REMAINING_RESPINS, state.remainingRespins());
        payload.put(COINS, state.coins().stream()
                .map(coin -> Map.<String, Object>of(
                        "row", coin.row(),
                        "col", coin.col(),
                        "value", coin.value().toPlainString()))
                .toList());
        // Omitted, not null: absent and "none earned yet" mean the same thing, and null would blow up
        // the sealed state's defensive copy.
        if (state.jackpotTier() != null) {
            payload.put(JACKPOT_TIER, state.jackpotTier());
        }
        payload.put(COMPLETED, state.completed());
        return payload;
    }

    /** Rebuilds the state a previous {@link #encode} wrote. */
    @SuppressWarnings("unchecked")
    public RespinState decode(Map<String, Object> payload) {
        try {
            List<RespinState.Coin> coins = new ArrayList<>();
            if (payload.get(COINS) instanceof List<?> raw) {
                for (Object entry : raw) {
                    Map<String, Object> coin = (Map<String, Object>) entry;
                    coins.add(new RespinState.Coin(
                            ((Number) coin.get("row")).intValue(),
                            ((Number) coin.get("col")).intValue(),
                            new BigDecimal(coin.get("value").toString())));
                }
            }
            Object remaining = payload.get(REMAINING_RESPINS);
            Object tier = payload.get(JACKPOT_TIER);
            return new RespinState(
                    remaining instanceof Number n ? n.intValue() : 0,
                    coins,
                    tier == null ? null : tier.toString(),
                    Boolean.TRUE.equals(payload.get(COMPLETED)));
        } catch (RuntimeException ex) {
            throw new RgsException(ErrorCode.INTERNAL_ERROR,
                    "Cannot deserialize respin payload: " + ex.getMessage(), ex);
        }
    }
}
