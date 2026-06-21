package com.velocity.rgs.game.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.velocity.rgs.game.feature.pickcollect.PickCollectFeatureView;
import com.velocity.rgs.session.domain.GameCommand;
import com.velocity.rgs.session.domain.GameState;
import lombok.Builder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Response body for {@code POST /api/v1/slot/init} (A.7). Carries the canonical session snapshot plus
 * the feature flags and available actions the client needs to render the lobby/UI correctly.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Builder
public record SlotInitResponse(
        String sessionId,
        long sessionVersion,
        String gameId,
        String mathVersion,
        String currency,
        BigDecimal balance,
        GameState currentState,
        int remainingFreeSpins,
        BigDecimal accumulatedFreeSpinsWin,
        BigDecimal currentBet,
        List<GameCommand> availableActions,
        Map<String, Object> featureFlags,
        PickCollectFeatureView activeFeatureView
) {
}
