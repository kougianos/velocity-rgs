package com.velocity.rgs.blackjack.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code POST /api/v1/blackjack/action}. {@code action} is a {@code BlackjackAction} name
 * (HIT/STAND/DOUBLE/SPLIT/INSURANCE); {@code handIndex} is optional - the server resolves the active hand
 * itself and only uses the index as a sanity check.
 */
public record BlackjackActionRequest(
        @NotBlank String gameId,
        @NotBlank String sessionId,
        @NotNull @Min(0) Long sessionVersion,
        @NotBlank String action,
        Integer handIndex
) {
}
