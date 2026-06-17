package com.velocity.rgs.rng;

/**
 * Immutable audit record of a single RNG draw (Appendix A.11). Captured in-memory by the round-scoped
 * {@link RngDrawSink} and persisted as {@code game_round.rng_draws} JSONB at round commit so any round
 * can be replayed deterministically via {@link DeterministicReplayRng}.
 *
 * @param boundExclusive the upper bound (exclusive) requested by the caller
 * @param value          the drawn value, always in {@code [0, boundExclusive)}
 * @param sequence       monotonic per-RNG-instance ordinal (0-based)
 */
public record RngDraw(int boundExclusive, int value, long sequence) {

    public RngDraw {
        if (boundExclusive <= 0) {
            throw new IllegalArgumentException("boundExclusive must be > 0");
        }
        if (value < 0 || value >= boundExclusive) {
            throw new IllegalArgumentException(
                    "value " + value + " out of range [0," + boundExclusive + ")");
        }
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must be >= 0");
        }
    }
}
