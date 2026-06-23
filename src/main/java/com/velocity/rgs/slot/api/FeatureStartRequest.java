package com.velocity.rgs.slot.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for {@code POST /api/v1/slot/feature/start} (A.7).
 * {@code featureType} must be one of {@code FREE_SPINS} or {@code PICK_COLLECT}.
 */
public record FeatureStartRequest(
        @NotBlank String gameId,
        @NotBlank String sessionId,
        @NotNull @Min(0) Long sessionVersion,
        @NotBlank @Pattern(regexp = "FREE_SPINS|PICK_COLLECT") String featureType
) {
}
