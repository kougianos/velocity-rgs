package com.velocity.rgs.game.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code POST /api/v1/slot/feature/pick} (A.7).
 */
public record FeaturePickRequest(
        @NotBlank String gameId,
        @NotBlank String sessionId,
        @NotNull @Min(0) Long sessionVersion,
        @NotNull @Min(0) Integer position
) {
}
