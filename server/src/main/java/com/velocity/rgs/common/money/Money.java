package com.velocity.rgs.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable monetary amount. Scale fixed to currency minor units (EUR/USD = 2), HALF_UP rounding.
 */
public record Money(BigDecimal amount, String currency) {

    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    private static final Set<String> SUPPORTED = Set.of("EUR", "USD");

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        if (!SUPPORTED.contains(currency)) {
            throw new IllegalArgumentException("Unsupported currency: " + currency);
        }
        int scale = minorUnitScale(currency);
        if (amount.scale() > scale) {
            throw new IllegalArgumentException(
                    "Amount scale " + amount.scale() + " exceeds " + scale + " for " + currency);
        }
        amount = amount.setScale(scale, ROUNDING);
    }

    public static Money of(BigDecimal amount, String currency) {
        return new Money(amount, currency);
    }

    public static Money zero(String currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public static Money fromMinor(long minor, String currency) {
        int scale = minorUnitScale(currency);
        BigDecimal value = BigDecimal.valueOf(minor, scale);
        return new Money(value, currency);
    }

    public long toMinor() {
        return amount.movePointRight(minorUnitScale(currency)).longValueExact();
    }

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money subtract(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public Money multiply(BigDecimal multiplier) {
        BigDecimal raw = amount.multiply(multiplier);
        return new Money(raw.setScale(minorUnitScale(currency), ROUNDING), currency);
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean greaterThanOrEqual(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount) >= 0;
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("Currency mismatch: " + currency + " vs " + other.currency);
        }
    }

    public static int minorUnitScale(String currency) {
        return switch (currency) {
            case "EUR", "USD" -> 2;
            default -> throw new IllegalArgumentException("Unsupported currency: " + currency);
        };
    }

    public static boolean isSupported(String currency) {
        return SUPPORTED.contains(currency);
    }
}
