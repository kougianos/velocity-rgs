package com.velocity.rgs.roulette.domain;

/**
 * The colour of a roulette pocket. On a European single-zero wheel the lone {@code 0} is {@link #GREEN};
 * the numbers 1–36 split evenly between {@link #RED} and {@link #BLACK} per the standard wheel layout
 * (the exact red/black assignment is server config - see {@code math.redNumbers} in the game JSON).
 */
public enum PocketColor {
    RED,
    BLACK,
    GREEN
}
