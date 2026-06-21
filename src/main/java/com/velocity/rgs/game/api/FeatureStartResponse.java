package com.velocity.rgs.game.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.velocity.rgs.game.feature.pickcollect.PickCollectFeatureView;
import com.velocity.rgs.session.domain.GameCommand;
import com.velocity.rgs.session.domain.GameState;
import lombok.Builder;

import java.math.BigDecimal;
import java.util.List;

/**
 * Response body for {@code POST /api/v1/slot/feature/start} (A.7).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Builder
public record FeatureStartResponse(
        String sessionId,
        long sessionVersion,
        GameState currentState,
        int remainingFreeSpins,
        BigDecimal accumulatedFreeSpinsWin,
        PickCollectFeatureView activeFeatureView,
        List<GameCommand> availableActions
) {
}
