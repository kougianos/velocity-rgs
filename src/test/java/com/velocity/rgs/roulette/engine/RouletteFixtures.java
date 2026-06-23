package com.velocity.rgs.roulette.engine;

import com.velocity.rgs.catalog.BetConfig;
import com.velocity.rgs.roulette.config.RouletteBetTypeConfig;
import com.velocity.rgs.roulette.config.RouletteLimits;
import com.velocity.rgs.roulette.config.RouletteMathDefinition;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static com.velocity.rgs.roulette.domain.RouletteBetKind.BLACK;
import static com.velocity.rgs.roulette.domain.RouletteBetKind.COLUMN_1;
import static com.velocity.rgs.roulette.domain.RouletteBetKind.COLUMN_2;
import static com.velocity.rgs.roulette.domain.RouletteBetKind.COLUMN_3;
import static com.velocity.rgs.roulette.domain.RouletteBetKind.DOZEN_1;
import static com.velocity.rgs.roulette.domain.RouletteBetKind.DOZEN_2;
import static com.velocity.rgs.roulette.domain.RouletteBetKind.DOZEN_3;
import static com.velocity.rgs.roulette.domain.RouletteBetKind.EVEN;
import static com.velocity.rgs.roulette.domain.RouletteBetKind.HIGH;
import static com.velocity.rgs.roulette.domain.RouletteBetKind.LOW;
import static com.velocity.rgs.roulette.domain.RouletteBetKind.ODD;
import static com.velocity.rgs.roulette.domain.RouletteBetKind.RED;
import static com.velocity.rgs.roulette.domain.RouletteBetKind.STRAIGHT;

/** Shared in-test European-wheel config so engine tests stay independent of the JSON loader. */
final class RouletteFixtures {

    static final Set<Integer> RED_NUMBERS = Set.of(
            1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36);

    private RouletteFixtures() {
    }

    static RouletteMathDefinition european() {
        List<RouletteBetTypeConfig> betTypes = List.of(
                new RouletteBetTypeConfig(STRAIGHT, 35),
                new RouletteBetTypeConfig(RED, 1),
                new RouletteBetTypeConfig(BLACK, 1),
                new RouletteBetTypeConfig(EVEN, 1),
                new RouletteBetTypeConfig(ODD, 1),
                new RouletteBetTypeConfig(LOW, 1),
                new RouletteBetTypeConfig(HIGH, 1),
                new RouletteBetTypeConfig(DOZEN_1, 2),
                new RouletteBetTypeConfig(DOZEN_2, 2),
                new RouletteBetTypeConfig(DOZEN_3, 2),
                new RouletteBetTypeConfig(COLUMN_1, 2),
                new RouletteBetTypeConfig(COLUMN_2, 2),
                new RouletteBetTypeConfig(COLUMN_3, 2));
        return def(betTypes);
    }

    static RouletteMathDefinition redOnly() {
        return def(List.of(new RouletteBetTypeConfig(RED, 1)));
    }

    private static RouletteMathDefinition def(List<RouletteBetTypeConfig> betTypes) {
        BetConfig betConfig = new BetConfig(List.of(new BigDecimal("1.00")), new BigDecimal("1.00"));
        RouletteLimits limits = new RouletteLimits(new BigDecimal("500.00"), new BigDecimal("2000.00"));
        return new RouletteMathDefinition("european-roulette", "v1", "EUROPEAN",
                new BigDecimal("97.30"), 37, RED_NUMBERS, betTypes, betConfig, limits);
    }
}
