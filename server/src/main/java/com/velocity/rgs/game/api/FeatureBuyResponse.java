package com.velocity.rgs.game.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.velocity.rgs.game.feature.pickcollect.PickCollectFeatureView;
import com.velocity.rgs.math.domain.BonusBuyType;
import com.velocity.rgs.session.domain.GameCommand;
import com.velocity.rgs.session.domain.GameState;
import lombok.Builder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Response body for {@code POST /api/v1/slot/feature/buy} (A.7).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Builder
public record FeatureBuyResponse(
        String sessionId,
        long sessionVersion,
        BonusBuyType buyType,
        BigDecimal cost,
        String currency,
        GameState enteredState,
        Map<String, Object> featureInitPayload,
        PickCollectFeatureView activeFeatureView,
        List<GameCommand> availableActions
) {
}
