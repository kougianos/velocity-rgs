package com.velocity.rgs.rng;

import java.util.List;

/**
 * Round-scoped audit collector for {@link RngDraw} entries (Appendix A.11). Every draw produced by an
 * RNG implementation is forwarded here in order; at round commit the {@link #drawn()} list is persisted
 * verbatim as {@code game_round.rng_draws}. The default implementation is an unbounded in-memory list;
 * callers must instantiate one fresh per round.
 */
public interface RngDrawSink {

    void record(RngDraw draw);

    /** Returns an unmodifiable view of all draws recorded so far, in capture order. */
    List<RngDraw> drawn();

    static RngDrawSink inMemory() {
        return new InMemoryRngDrawSink();
    }

    /**
     * Sink that records nothing, for callers that draw numbers they will never replay.
     *
     * <p>Only the RTP simulator qualifies: it draws tens of millions of numbers purely to measure
     * convergence, and capturing them costs an {@link RngDraw} allocation per draw plus an unbounded
     * list per round, for a trail nothing reads. Measured at ~2x the cost of the simulated spin itself.
     *
     * <p><strong>Never use this on a live round.</strong> {@code game_round.rng_draws} would persist
     * empty and the round would be permanently unreplayable - the audit trail in
     * {@link DeterministicReplayRng} exists precisely because those draws were kept.
     */
    static RngDrawSink discarding() {
        return DiscardingRngDrawSink.INSTANCE;
    }
}
