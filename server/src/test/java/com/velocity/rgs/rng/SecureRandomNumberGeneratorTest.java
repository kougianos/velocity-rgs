package com.velocity.rgs.rng;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecureRandomNumberGeneratorTest {

    @Test
    void boundsAreRespectedAcrossManyDraws() {
        RngDrawSink sink = RngDrawSink.inMemory();
        SecureRandomNumberGenerator rng = new SecureRandomNumberGenerator(sink);

        int bound = 32;
        int draws = 5_000;
        IntStream.range(0, draws).forEach(i -> {
            int v = rng.nextIndex(bound);
            assertThat(v).isGreaterThanOrEqualTo(0).isLessThan(bound);
        });

        assertThat(sink.drawn()).hasSize(draws);
    }

    @Test
    void auditListIsPopulatedInOrderWithMonotonicSequence() {
        RngDrawSink sink = RngDrawSink.inMemory();
        SecureRandomNumberGenerator rng = new SecureRandomNumberGenerator(sink);

        int[] bounds = {32, 64, 17, 5, 100};
        for (int b : bounds) {
            rng.nextIndex(b);
        }

        List<RngDraw> draws = sink.drawn();
        assertThat(draws).hasSize(bounds.length);
        for (int i = 0; i < bounds.length; i++) {
            RngDraw d = draws.get(i);
            assertThat(d.boundExclusive()).isEqualTo(bounds[i]);
            assertThat(d.sequence()).isEqualTo(i);
            assertThat(d.value()).isGreaterThanOrEqualTo(0).isLessThan(bounds[i]);
        }
    }

    @Test
    void recordedValuesMatchUnderlyingRandom() {
        // Inject a SecureRandom seeded for predictability so we can assert value parity with the sink.
        SecureRandom seeded = new SecureRandom() {
            private int i = 0;
            private final int[] values = {7, 0, 5, 1, 3};

            @Override
            public int nextInt(int bound) {
                return values[i++] % bound;
            }
        };
        RngDrawSink sink = RngDrawSink.inMemory();
        SecureRandomNumberGenerator rng = new SecureRandomNumberGenerator(seeded, sink);

        int v0 = rng.nextIndex(10);
        int v1 = rng.nextIndex(10);
        int v2 = rng.nextIndex(10);

        assertThat(v0).isEqualTo(7);
        assertThat(v1).isEqualTo(0);
        assertThat(v2).isEqualTo(5);
        assertThat(sink.drawn()).extracting(RngDraw::value).containsExactly(7, 0, 5);
        assertThat(sink.drawn()).extracting(RngDraw::sequence).containsExactly(0L, 1L, 2L);
    }

    @Test
    void rejectsNonPositiveBound() {
        SecureRandomNumberGenerator rng = new SecureRandomNumberGenerator(RngDrawSink.inMemory());
        assertThatThrownBy(() -> rng.nextIndex(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rng.nextIndex(-3)).isInstanceOf(IllegalArgumentException.class);
    }
}
