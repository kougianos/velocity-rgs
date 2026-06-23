package com.velocity.rgs.roulette.engine;

import com.velocity.rgs.roulette.domain.RouletteBetKind;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A single chip placed on the table for one spin: the {@link RouletteBetKind}, the target {@code number}
 * (only for {@link RouletteBetKind#STRAIGHT}; {@code null} otherwise), and the staked {@code amount}. This is
 * the engine's input — the controller maps the wire request into a list of these and the service validates
 * them against the game's bet types, chip values and table limits before evaluation.
 */
public record RouletteBet(RouletteBetKind kind, Integer number, BigDecimal amount) {

    public RouletteBet {
        Objects.requireNonNull(kind, "bet.kind");
        Objects.requireNonNull(amount, "bet.amount");
    }
}
