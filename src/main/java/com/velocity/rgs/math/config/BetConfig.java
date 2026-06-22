package com.velocity.rgs.math.config;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The set of stakes a player may wager on a game, loaded from the {@code math.betConfig} block of
 * {@code games/<gameId>/<mathVersion>.json}. The server is the single authority on which bets are legal: the
 * client renders the {@link #values()} as a stake selector and seeds it with {@link #defaultBet()}, but every
 * spin re-validates the incoming stake against this list ({@link #isValidBet(BigDecimal)}) so a tampered or
 * stale client cannot wager an off-grid amount. {@link #minBet()} / {@link #maxBet()} are derived from the
 * list so the advertised bounds can never disagree with the selectable values.
 *
 * <p>Comparisons use {@link BigDecimal#compareTo} rather than {@code equals} so {@code 1.0}, {@code 1.00} and
 * {@code 1} are treated as the same stake regardless of how the client serialized it.
 */
public record BetConfig(List<BigDecimal> values, BigDecimal defaultBet) {

    public BetConfig {
        Objects.requireNonNull(values, "betConfig.values");
        Objects.requireNonNull(defaultBet, "betConfig.defaultBet");
        if (values.isEmpty()) {
            throw new IllegalArgumentException("betConfig.values must not be empty");
        }
        List<BigDecimal> sorted = new ArrayList<>(values);
        sorted.sort(BigDecimal::compareTo);
        BigDecimal previous = null;
        for (BigDecimal value : sorted) {
            if (value.signum() <= 0) {
                throw new IllegalArgumentException("betConfig.values must all be positive, found " + value);
            }
            if (previous != null && previous.compareTo(value) == 0) {
                throw new IllegalArgumentException("betConfig.values has a duplicate stake: " + value);
            }
            previous = value;
        }
        values = List.copyOf(sorted);
        if (!containsBet(values, defaultBet)) {
            throw new IllegalArgumentException(
                    "betConfig.defaultBet " + defaultBet + " is not one of betConfig.values " + values);
        }
    }

    /** Lowest selectable stake (first entry once sorted). */
    public BigDecimal minBet() {
        return values.get(0);
    }

    /** Highest selectable stake (last entry once sorted). */
    public BigDecimal maxBet() {
        return values.get(values.size() - 1);
    }

    /** True when {@code bet} matches one of the configured stakes by numeric value (scale-insensitive). */
    public boolean isValidBet(BigDecimal bet) {
        return bet != null && containsBet(values, bet);
    }

    private static boolean containsBet(List<BigDecimal> values, BigDecimal bet) {
        for (BigDecimal value : values) {
            if (value.compareTo(bet) == 0) {
                return true;
            }
        }
        return false;
    }
}
