package com.velocity.rgs.slot.api;

import com.velocity.rgs.slot.math.domain.BonusBuyType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Request body for {@code POST /api/v1/slot/feature/buy} (A.7).
 */
public record FeatureBuyRequest(
        @NotBlank String gameId,
        @NotBlank String sessionId,
        @NotNull @Min(0) Long sessionVersion,
        @NotNull BonusBuyType buyType,
        @NotNull @DecimalMin(value = "0.00", inclusive = false) BigDecimal betSize
) {
}
