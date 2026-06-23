package com.velocity.rgs.slot.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.velocity.rgs.slot.feature.pickcollect.PickCollectFeatureView;
import com.velocity.rgs.slot.math.domain.PickTileType;
import com.velocity.rgs.session.domain.GameCommand;
import com.velocity.rgs.session.domain.GameState;
import lombok.Builder;

import java.math.BigDecimal;
import java.util.List;

/**
 * Response body for {@code POST /api/v1/slot/feature/pick} (A.7).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Builder
public record FeaturePickResponse(
        String sessionId,
        long sessionVersion,
        int position,
        PickTileType resolvedTileType,
        BigDecimal resolvedValue,
        BigDecimal currentCollected,
        int remainingPicks,
        boolean featureCompleted,
        BigDecimal featureTotalWin,
        GameState currentState,
        PickCollectFeatureView activeFeatureView,
        List<String> reasonCodes,
        List<GameCommand> availableActions
) {
}
