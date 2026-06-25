package com.velocity.rgs.blackjack.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** Request body for {@code POST /api/v1/blackjack/deal} - starts a new round with a single base bet. */
public record BlackjackDealRequest(
        @NotBlank String gameId,
        @NotBlank String sessionId,
        @NotNull @Min(0) Long sessionVersion,
        @NotNull @DecimalMin(value = "0.00", inclusive = false) BigDecimal bet
) {
}
