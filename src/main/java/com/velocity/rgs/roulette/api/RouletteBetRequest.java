package com.velocity.rgs.roulette.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * One placed bet in a spin request: the bet {@code type} (a {@code RouletteBetKind} name), the target
 * {@code number} for a straight-up bet ({@code null}/ignored for outside bets), and the staked {@code amount}.
 * The server validates the type, number range, chip value and table limits before settling.
 */
public record RouletteBetRequest(
        @NotBlank String type,
        Integer number,
        @NotNull @DecimalMin(value = "0.00", inclusive = false) BigDecimal amount
) {
}
