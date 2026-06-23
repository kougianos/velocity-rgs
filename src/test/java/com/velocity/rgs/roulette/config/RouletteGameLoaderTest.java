package com.velocity.rgs.roulette.config;

import com.velocity.rgs.roulette.domain.RouletteBetKind;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies the committed roulette game JSON loads and parses into a valid math model. */
class RouletteGameLoaderTest {

    private final RouletteGameLoader loader = new RouletteGameLoader();

    @Test
    void loadsEuropeanRoulette() {
        RouletteGameDefinition def = loader.load("european-roulette", "v1");

        assertThat(def.gameId()).isEqualTo("european-roulette");
        assertThat(def.mathVersion()).isEqualTo("v1");

        RouletteMathDefinition m = def.math();
        assertThat(m.variant()).isEqualTo("EUROPEAN");
        assertThat(m.pocketCount()).isEqualTo(37);
        assertThat(m.redNumbers()).hasSize(18);
        assertThat(m.betTypes()).hasSize(13);
        assertThat(m.betType(RouletteBetKind.STRAIGHT)).isPresent();
        assertThat(m.betType(RouletteBetKind.STRAIGHT).orElseThrow().payout()).isEqualTo(35);
        assertThat(m.betType(RouletteBetKind.RED).orElseThrow().payout()).isEqualTo(1);
        assertThat(m.betType(RouletteBetKind.DOZEN_1).orElseThrow().payout()).isEqualTo(2);
        assertThat(m.betConfig().defaultBet()).isEqualByComparingTo("1.00");
        assertThat(m.betConfig().isValidBet(new BigDecimal("0.50"))).isTrue();
        assertThat(m.limits().maxTotalBet()).isEqualByComparingTo("2000.00");
        assertThat(m.limits().maxBetPerSpot()).isEqualByComparingTo("500.00");

        assertThat(def.presentation().title()).isEqualTo("European Roulette");
        assertThat(def.presentation().info().specs()).isNotEmpty();
    }

    @Test
    void missingGameThrows() {
        assertThatThrownBy(() -> loader.load("does-not-exist", "v1"))
                .isInstanceOf(IllegalStateException.class);
    }
}
