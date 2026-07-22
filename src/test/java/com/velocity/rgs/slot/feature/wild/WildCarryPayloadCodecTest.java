package com.velocity.rgs.slot.feature.wild;

import com.velocity.rgs.slot.math.engine.WildFeatureEngine.WildCell;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The carry's shape is written by the spin loop and read back by a replay that can run months later, so
 * the two ends have to agree on it exactly. That agreement is what this covers - not the wild mechanic
 * itself, which {@code WildFeatureEngineTest} owns.
 */
class WildCarryPayloadCodecTest {

    private final WildCarryPayloadCodec codec = new WildCarryPayloadCodec();

    @Test
    void roundTripsEveryCellVerbatim() {
        List<WildCell> wilds = List.of(new WildCell(0, 4, 3), new WildCell(2, 1, 1));

        assertThat(codec.decode(codec.encode(wilds))).containsExactlyElementsOf(wilds);
    }

    /**
     * An empty carry has to survive the round trip as an empty carry, not as a missing one. It is the
     * difference between "this spin started clean" and "nobody recorded what this spin started with",
     * and the replay path refuses the second - so encoding the first as an absent key would refuse every
     * first spin of a feature.
     */
    @Test
    void encodesAnEmptyCarryAsAPresentEmptyList() {
        Map<String, Object> payload = codec.encode(List.of());

        assertThat(payload).containsKey(WildCarryPayloadCodec.KEY);
        assertThat(codec.decode(payload)).isEmpty();
    }

    /** Null values would blow up the sealed session state's {@code Map.copyOf} on the way back out. */
    @Test
    void neverWritesANullValue() {
        assertThat(codec.encode(List.of(new WildCell(1, 1, 1))))
                .allSatisfy((k, v) -> assertThat(v).isNotNull());
    }

    /**
     * A session payload written before sticky wilds existed has no entry at all, and a spin must not
     * fail over that - so an unreadable carry reads as no carry rather than as an error.
     */
    @Test
    void readsAnythingItCannotUnderstandAsNoCarry() {
        assertThat(codec.decode(null)).isEmpty();
        assertThat(codec.decode(Map.of())).isEmpty();
        assertThat(codec.decode(Map.of("someOtherFeature", "payload"))).isEmpty();
        assertThat(codec.decode(Map.of(WildCarryPayloadCodec.KEY, "not a list"))).isEmpty();
        assertThat(codec.decode(Map.of(WildCarryPayloadCodec.KEY, List.of(Map.of("row", 1))))).isEmpty();
    }
}
