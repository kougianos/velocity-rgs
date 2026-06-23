package com.velocity.rgs.roulette.api;

import jakarta.validation.constraints.NotBlank;

/** Request body for {@code POST /api/v1/roulette/init}. */
public record RouletteInitRequest(
        @NotBlank String gameId,
        @NotBlank String currency
) {
}
