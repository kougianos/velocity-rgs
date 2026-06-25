package com.velocity.rgs.roulette.config;

import com.velocity.rgs.roulette.domain.RouletteBetKind;

import java.util.Objects;

/**
 * One configurable bet type: its {@link RouletteBetKind} and its "to-one" {@code payout} (e.g. 35 for a
 * straight-up, 2 for a dozen/column, 1 for the even-money bets). A winning bet returns
 * {@code stake × (payout + 1)} - the original stake plus the profit. Authored in {@code math.betTypes} so the
 * pay schedule is data-driven; the covered numbers come from universal roulette geometry, not from config.
 */
public record RouletteBetTypeConfig(RouletteBetKind kind, int payout) {

    public RouletteBetTypeConfig {
        Objects.requireNonNull(kind, "betType.kind");
        if (payout <= 0) {
            throw new IllegalArgumentException("betType.payout must be positive for " + kind + ", found " + payout);
        }
    }
}
