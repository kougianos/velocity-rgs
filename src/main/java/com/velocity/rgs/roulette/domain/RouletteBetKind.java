package com.velocity.rgs.roulette.domain;

/**
 * The kinds of bet a player can place on the (European) roulette table. {@link #STRAIGHT} is the only inside
 * bet exposed here - it targets a single number carried on the bet itself. The rest are outside bets whose
 * covered numbers are fixed by universal roulette geometry (resolved server-side in
 * {@code RouletteEvaluator}); only their colour mapping (red/black) is configurable. Standard "to-one"
 * payouts: straight 35, dozens/columns 2, everything else 1 - all authored in the game JSON so the engine
 * stays data-driven.
 */
public enum RouletteBetKind {
    /** A single number, 0–36. The chosen number travels on the bet (a straight-up bet). */
    STRAIGHT,
    RED,
    BLACK,
    EVEN,
    ODD,
    /** Low half - numbers 1–18. */
    LOW,
    /** High half - numbers 19–36. */
    HIGH,
    /** First dozen - 1–12. */
    DOZEN_1,
    /** Second dozen - 13–24. */
    DOZEN_2,
    /** Third dozen - 25–36. */
    DOZEN_3,
    /** First column - 1,4,7,…,34. */
    COLUMN_1,
    /** Second column - 2,5,8,…,35. */
    COLUMN_2,
    /** Third column - 3,6,9,…,36. */
    COLUMN_3;

    /** True for the only inside bet - the one whose target number is supplied by the player. */
    public boolean requiresNumber() {
        return this == STRAIGHT;
    }
}
