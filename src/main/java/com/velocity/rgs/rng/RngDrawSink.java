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
}
