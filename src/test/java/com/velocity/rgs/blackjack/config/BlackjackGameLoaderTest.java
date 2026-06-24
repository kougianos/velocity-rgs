package com.velocity.rgs.blackjack.config;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies the committed blackjack game JSON loads and parses into a valid math model. */
class BlackjackGameLoaderTest {

    private final BlackjackGameLoader loader = new BlackjackGameLoader();

    @Test
    void loadsClassicBlackjack() {
        BlackjackGameDefinition def = loader.load("classic-blackjack", "v1");

        assertThat(def.gameId()).isEqualTo("classic-blackjack");
        assertThat(def.mathVersion()).isEqualTo("v1");

        BlackjackMathDefinition m = def.math();
        assertThat(m.variant()).isEqualTo("CLASSIC");
        assertThat(m.decks()).isEqualTo(6);
        assertThat(m.dealerHitsSoft17()).isFalse();
        assertThat(m.blackjackPayout()).isEqualByComparingTo("1.5");
        assertThat(m.doubleAfterSplit()).isTrue();
        assertThat(m.maxSplits()).isEqualTo(2);
        assertThat(m.maxHands()).isEqualTo(3);
        assertThat(m.insuranceEnabled()).isTrue();
        assertThat(m.insurancePayout()).isEqualTo(2);
        assertThat(m.betConfig().defaultBet()).isEqualByComparingTo("5.00");
        assertThat(m.betConfig().isValidBet(new BigDecimal("1.00"))).isTrue();
        assertThat(m.limits().maxBet()).isEqualByComparingTo("500.00");

        assertThat(def.presentation().title()).isEqualTo("Classic Blackjack");
        assertThat(def.presentation().info().specs()).isNotEmpty();
    }

    @Test
    void missingGameThrows() {
        assertThatThrownBy(() -> loader.load("does-not-exist", "v1"))
                .isInstanceOf(IllegalStateException.class);
    }
}
