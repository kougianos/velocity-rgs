package com.velocity.rgs.roulette.engine;

import com.velocity.rgs.roulette.config.RouletteMathDefinition;
import com.velocity.rgs.roulette.domain.RouletteBetKind;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Universal roulette geometry - the set of numbers each bet kind covers on a standard single-zero layout
 * (numbers {@code 1..highest}, with {@code 0} winning only straight-up bets on {@code 0}). This is the game's
 * rulebook, so it lives in server code (never the client): inside/outside coverage is derived here, while the
 * only configurable input is the red/black colour map ({@code math.redNumbers}). The {@code highest} number
 * is assumed divisible by 6 (36 on a European wheel) so the halves, dozens and columns partition cleanly.
 */
public final class RouletteGeometry {

    private RouletteGeometry() {
    }

    /**
     * The numbers that {@code kind} covers. For {@link RouletteBetKind#STRAIGHT} this is exactly
     * {@code {number}} (which may be {@code 0}); for every outside bet the coverage is derived from the wheel
     * layout and excludes {@code 0}.
     */
    public static Set<Integer> coveredNumbers(RouletteBetKind kind, Integer number,
                                              RouletteMathDefinition math) {
        int max = math.highestNumber();
        Set<Integer> covered = new LinkedHashSet<>();
        switch (kind) {
            case STRAIGHT -> {
                if (number == null || number < 0 || number > max) {
                    throw new IllegalArgumentException(
                            "STRAIGHT bet requires a number in [0, " + max + "], found " + number);
                }
                covered.add(number);
            }
            case RED -> covered.addAll(math.redNumbers());
            case BLACK -> {
                for (int n = 1; n <= max; n++) {
                    if (!math.redNumbers().contains(n)) {
                        covered.add(n);
                    }
                }
            }
            case EVEN -> addRangeIf(covered, 1, max, n -> n % 2 == 0);
            case ODD -> addRangeIf(covered, 1, max, n -> n % 2 == 1);
            case LOW -> addRange(covered, 1, max / 2);
            case HIGH -> addRange(covered, max / 2 + 1, max);
            case DOZEN_1 -> addRange(covered, 1, max / 3);
            case DOZEN_2 -> addRange(covered, max / 3 + 1, 2 * max / 3);
            case DOZEN_3 -> addRange(covered, 2 * max / 3 + 1, max);
            case COLUMN_1 -> addRangeIf(covered, 1, max, n -> n % 3 == 1);
            case COLUMN_2 -> addRangeIf(covered, 1, max, n -> n % 3 == 2);
            case COLUMN_3 -> addRangeIf(covered, 1, max, n -> n % 3 == 0);
        }
        return covered;
    }

    private static void addRange(Set<Integer> set, int from, int to) {
        for (int n = from; n <= to; n++) {
            set.add(n);
        }
    }

    private static void addRangeIf(Set<Integer> set, int from, int to, java.util.function.IntPredicate p) {
        for (int n = from; n <= to; n++) {
            if (p.test(n)) {
                set.add(n);
            }
        }
    }
}
