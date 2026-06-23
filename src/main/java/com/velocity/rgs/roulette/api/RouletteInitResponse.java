package com.velocity.rgs.roulette.api;

import com.velocity.rgs.session.domain.GameCommand;
import lombok.Builder;

import java.math.BigDecimal;
import java.util.List;

/** Response body for {@code POST /api/v1/roulette/init}. */
@Builder
public record RouletteInitResponse(
        String sessionId,
        long sessionVersion,
        String gameId,
        String mathVersion,
        String currency,
        BigDecimal balance,
        List<BigDecimal> betValues,
        BigDecimal defaultBet,
        List<GameCommand> availableActions
) {
}
