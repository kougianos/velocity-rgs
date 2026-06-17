package com.velocity.rgs.game.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Request body for {@code POST /api/v1/slot/spin} (A.7).
 */
public record SpinRequest(
        @NotBlank String gameId,
        @NotBlank String sessionId,
        @NotNull @Min(0) Long sessionVersion,
        @NotNull @DecimalMin(value = "0.00", inclusive = false) BigDecimal betSize,
        boolean powerBetActive
) {
}
