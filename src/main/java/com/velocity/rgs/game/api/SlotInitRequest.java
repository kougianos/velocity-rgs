package com.velocity.rgs.game.api;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /api/v1/slot/init} (A.7).
 */
public record SlotInitRequest(
        @NotBlank String gameId,
        @NotBlank String currency
) {
}
