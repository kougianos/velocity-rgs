package com.velocity.rgs.slot.feature.wild;

import com.velocity.rgs.slot.math.engine.WildFeatureEngine;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Round-trips the sticky/walking wilds a spin carries in, through the plain {@code Map} that lands in
 * both {@code game_session.active_feature_payload} (next spin's input) and {@code game_round
 * .feature_context} (the replay's input).
 *
 * <p>Its own type because those two writers have to agree byte for byte, and they used to be a pair of
 * private methods on one service with only the session reader for company. The moment a round records
 * the same carry, the shape stops being an implementation detail of the spin loop and becomes a
 * persistence contract that a replay months later has to read back - so it gets a name and a test.
 *
 * <p>{@link #decode} is deliberately forgiving: a payload written before sticky wilds existed simply has
 * no entry under {@link #KEY}, and anything unreadable is treated the same way rather than failing a
 * spin over a presentation detail. The <em>absence of the whole payload</em> is what carries meaning on
 * the replay path, and that distinction is made by the caller, which knows whether it was ever written.
 */
@Component
public class WildCarryPayloadCodec {

    /** Key under which sticky/walking wilds ride, in either payload. */
    public static final String KEY = "stickyWilds";

    private static final String ROW = "row";
    private static final String COL = "col";
    private static final String REMAINING_SPINS = "remainingSpins";

    /** The carry as a JSON-safe map. Never contains a null value, at any depth. */
    public Map<String, Object> encode(List<WildFeatureEngine.WildCell> wilds) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(KEY, wilds == null ? List.of() : wilds.stream()
                .map(w -> Map.<String, Object>of(
                        ROW, w.row(),
                        COL, w.col(),
                        REMAINING_SPINS, w.remainingSpins()))
                .toList());
        return payload;
    }

    /** Rebuilds the carry a previous {@link #encode} wrote; empty when there is none to read. */
    @SuppressWarnings("unchecked")
    public List<WildFeatureEngine.WildCell> decode(Map<String, Object> payload) {
        if (payload == null || !(payload.get(KEY) instanceof List<?> raw)) {
            return List.of();
        }
        try {
            List<WildFeatureEngine.WildCell> cells = new ArrayList<>(raw.size());
            for (Object entry : raw) {
                Map<String, Object> cell = (Map<String, Object>) entry;
                cells.add(new WildFeatureEngine.WildCell(
                        ((Number) cell.get(ROW)).intValue(),
                        ((Number) cell.get(COL)).intValue(),
                        ((Number) cell.get(REMAINING_SPINS)).intValue()));
            }
            return List.copyOf(cells);
        } catch (RuntimeException ex) {
            return List.of();
        }
    }
}
