package com.velocity.rgs.roulette.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** Request body for {@code POST /api/v1/roulette/spin}. */
public record RouletteSpinRequest(
        @NotBlank String gameId,
        @NotBlank String sessionId,
        @NotNull @Min(0) Long sessionVersion,
        @NotEmpty List<@Valid RouletteBetRequest> bets
) {
}
