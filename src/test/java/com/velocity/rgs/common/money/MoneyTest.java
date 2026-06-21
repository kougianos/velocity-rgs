package com.velocity.rgs.common.money;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void rejectsAmountWithScaleGreaterThanCurrencyMinorUnits() {
        assertThatThrownBy(() -> Money.of(new BigDecimal("1.234"), "EUR"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scale");
    }

    @Test
    void acceptsAmountWithSmallerScaleAndNormalizes() {
        Money m = Money.of(new BigDecimal("1.5"), "EUR");
        assertThat(m.amount().toPlainString()).isEqualTo("1.50");
    }

    @Test
    void rejectsUnsupportedCurrency() {
        assertThatThrownBy(() -> Money.of(BigDecimal.ONE, "GBP"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void minorUnitsRoundTrip() {
        Money m = Money.fromMinor(12345L, "USD");
        assertThat(m.amount().toPlainString()).isEqualTo("123.45");
        assertThat(m.toMinor()).isEqualTo(12345L);
    }

    @Test
    void addAndSubtractEnforceSameCurrency() {
        Money a = Money.of(new BigDecimal("10.00"), "EUR");
        Money b = Money.of(new BigDecimal("3.50"), "EUR");
        assertThat(a.add(b).amount().toPlainString()).isEqualTo("13.50");
        assertThat(a.subtract(b).amount().toPlainString()).isEqualTo("6.50");

        Money usd = Money.of(BigDecimal.ONE, "USD");
        assertThatThrownBy(() -> a.add(usd)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void multiplyAppliesHalfUp() {
        Money m = Money.of(new BigDecimal("1.00"), "EUR");
        assertThat(m.multiply(new BigDecimal("1.505")).amount().toPlainString()).isEqualTo("1.51");
    }
}
