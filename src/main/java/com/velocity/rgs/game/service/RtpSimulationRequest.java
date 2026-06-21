package com.velocity.rgs.game.service;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.math.BigDecimal;

/**
 * Input for {@link RtpSimulationService#run(RtpSimulationRequest, String)} (M7 Task 7.6 / A.19).
 * All fields are immutable; null counts default to zero so callers can run a single channel only.
 */
@Builder
public record RtpSimulationRequest(
        @NotBlank String gameId,
        @NotBlank String mathVersion,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal bet,
        @Min(0) long spinsBaseGame,
        @Min(0) long spinsPowerBet,
        @Min(0) long spinsBonusBuyFreeSpins,
        PickStrategy pickStrategy
) {
    public RtpSimulationRequest {
        if (pickStrategy == null) {
            pickStrategy = PickStrategy.RANDOM_UNOPENED;
        }
    }

    public enum PickStrategy {
        SEQUENTIAL, RANDOM_UNOPENED, COLLECT_FIRST
    }
}
