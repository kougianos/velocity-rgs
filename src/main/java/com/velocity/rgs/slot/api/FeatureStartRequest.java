package com.velocity.rgs.slot.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for {@code POST /api/v1/slot/feature/start} (A.7).
 * {@code featureType} must be one of {@code FREE_SPINS}, {@code PICK_COLLECT} or {@code RESPIN}.
 *
 * <p>The pattern has to list every feature the FSM accepts: a type missing here is rejected at
 * validation with a bare {@code VALIDATION_ERROR}, before {@code SlotEngineService} ever sees it, so
 * the feature simply cannot be entered however correct the rest of the wiring is.
 */
public record FeatureStartRequest(
        @NotBlank String gameId,
        @NotBlank String sessionId,
        @NotNull @Min(0) Long sessionVersion,
        @NotBlank @Pattern(regexp = "FREE_SPINS|PICK_COLLECT|RESPIN") String featureType
) {
}
