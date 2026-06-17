package com.velocity.rgs.rng;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Default round-local {@link RngDrawSink} implementation. Not thread-safe by design. */
final class InMemoryRngDrawSink implements RngDrawSink {

    private final List<RngDraw> draws = new ArrayList<>();

    @Override
    public void record(RngDraw draw) {
        draws.add(Objects.requireNonNull(draw, "draw"));
    }

    @Override
    public List<RngDraw> drawn() {
        return Collections.unmodifiableList(draws);
    }
}
