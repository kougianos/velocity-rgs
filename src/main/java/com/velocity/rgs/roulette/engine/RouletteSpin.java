package com.velocity.rgs.roulette.engine;

import com.velocity.rgs.roulette.domain.PocketColor;

/** The pocket the ball landed in for one spin: the winning {@code number} and its {@link PocketColor}. */
public record RouletteSpin(int number, PocketColor color) {
}
