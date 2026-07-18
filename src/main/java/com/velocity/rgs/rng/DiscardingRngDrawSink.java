package com.velocity.rgs.rng;

import java.util.List;

/**
 * {@link RngDrawSink} that captures nothing. Stateless and therefore safe to share across threads,
 * unlike {@link InMemoryRngDrawSink}.
 *
 * <p><strong>Simulation only.</strong> See {@link RngDrawSink#discarding()} for why a live round must
 * never use this.
 */
enum DiscardingRngDrawSink implements RngDrawSink {
    INSTANCE;

    @Override
    public void record(RngDraw draw) {
        // Intentionally dropped.
    }

    @Override
    public List<RngDraw> drawn() {
        return List.of();
    }
}
