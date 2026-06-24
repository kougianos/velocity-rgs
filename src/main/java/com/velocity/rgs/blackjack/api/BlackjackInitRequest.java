package com.velocity.rgs.blackjack.api;

import jakarta.validation.constraints.NotBlank;

/** Request body for {@code POST /api/v1/blackjack/init}. */
public record BlackjackInitRequest(
        @NotBlank String gameId,
        @NotBlank String currency
) {
}
