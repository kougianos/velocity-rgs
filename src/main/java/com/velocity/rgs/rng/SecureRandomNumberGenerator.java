package com.velocity.rgs.rng;

import java.security.SecureRandom;
import java.util.Objects;

/**
 * Cryptographically-strong RNG wrapping {@link SecureRandom} (Appendix A.11 / Milestone 2 Task 2.1).
 *
 * <p>Each call to {@link #nextIndex(int)} produces an {@link RngDraw} that is appended in order to the
 * caller-supplied {@link RngDrawSink}. The sink is the canonical audit trail used to reconstruct the
 * round deterministically through {@link DeterministicReplayRng}.
 *
 * <p>This class is stateful (sequence counter) and must be instantiated once per round; it is not a
 * Spring bean.
 */
public final class SecureRandomNumberGenerator implements RandomNumberGenerator {

    private final SecureRandom secureRandom;
    private final RngDrawSink sink;
    private long sequence;

    public SecureRandomNumberGenerator(RngDrawSink sink) {
        this(new SecureRandom(), sink);
    }

    SecureRandomNumberGenerator(SecureRandom secureRandom, RngDrawSink sink) {
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    @Override
    public int nextIndex(int boundExclusive) {
        if (boundExclusive <= 0) {
            throw new IllegalArgumentException("boundExclusive must be > 0");
        }
        int value = secureRandom.nextInt(boundExclusive);
        sink.record(new RngDraw(boundExclusive, value, sequence++));
        return value;
    }
}
