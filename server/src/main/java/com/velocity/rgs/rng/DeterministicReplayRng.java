package com.velocity.rgs.rng;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Replay RNG (Milestone 2 Task 2.2 / Appendix A.11). Pops pre-recorded {@link RngDraw} entries in
 * sequence and returns their stored {@code value}. The recorded bounds must match the caller's request
 * exactly so any divergence between production code and the replay context is detected immediately.
 *
 * <p>Used by {@code ReplayService} (Milestone 6) and by unit tests that need a deterministic RNG.
 */
public final class DeterministicReplayRng implements RandomNumberGenerator {

    private final Deque<RngDraw> queue;

    public DeterministicReplayRng(List<RngDraw> recorded) {
        Objects.requireNonNull(recorded, "recorded");
        this.queue = new ArrayDeque<>(recorded);
    }

    @Override
    public int nextIndex(int boundExclusive) {
        if (boundExclusive <= 0) {
            throw new IllegalArgumentException("boundExclusive must be > 0");
        }
        RngDraw next = queue.pollFirst();
        if (next == null) {
            throw new IllegalStateException("replay RNG drained: no further draws recorded");
        }
        if (next.boundExclusive() != boundExclusive) {
            throw new IllegalStateException(
                    "replay bound mismatch at sequence " + next.sequence()
                            + ": expected " + next.boundExclusive() + ", got " + boundExclusive);
        }
        return next.value();
    }

    /** Number of draws still queued. */
    public int remaining() {
        return queue.size();
    }
}
