package com.velocity.rgs.rng;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeterministicReplayRngTest {

    @Test
    void replaysRecordedSequenceExactly() {
        List<RngDraw> recorded = List.of(
                new RngDraw(32, 14, 0),
                new RngDraw(32, 28, 1),
                new RngDraw(32,  4, 2),
                new RngDraw(32, 31, 3),
                new RngDraw(32,  0, 4)
        );
        DeterministicReplayRng rng = new DeterministicReplayRng(recorded);

        assertThat(rng.nextIndex(32)).isEqualTo(14);
        assertThat(rng.nextIndex(32)).isEqualTo(28);
        assertThat(rng.nextIndex(32)).isEqualTo(4);
        assertThat(rng.nextIndex(32)).isEqualTo(31);
        assertThat(rng.nextIndex(32)).isEqualTo(0);
        assertThat(rng.remaining()).isZero();
    }

    @Test
    void throwsWhenDrained() {
        DeterministicReplayRng rng = new DeterministicReplayRng(List.of(new RngDraw(10, 3, 0)));
        rng.nextIndex(10);
        assertThatThrownBy(() -> rng.nextIndex(10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("drained");
    }

    @Test
    void detectsBoundMismatch() {
        DeterministicReplayRng rng = new DeterministicReplayRng(List.of(new RngDraw(32, 5, 0)));
        assertThatThrownBy(() -> rng.nextIndex(64))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bound mismatch");
    }

    @Test
    void roundTripsThroughSecureRng() {
        RngDrawSink sink = RngDrawSink.inMemory();
        SecureRandomNumberGenerator capture = new SecureRandomNumberGenerator(sink);
        int[] bounds = {32, 64, 17, 5, 100, 12, 12, 12, 12, 12};
        int[] produced = new int[bounds.length];
        for (int i = 0; i < bounds.length; i++) {
            produced[i] = capture.nextIndex(bounds[i]);
        }

        DeterministicReplayRng replay = new DeterministicReplayRng(sink.drawn());
        for (int i = 0; i < bounds.length; i++) {
            assertThat(replay.nextIndex(bounds[i])).isEqualTo(produced[i]);
        }
        assertThat(replay.remaining()).isZero();
    }
}
