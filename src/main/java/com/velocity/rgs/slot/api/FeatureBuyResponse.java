package com.velocity.rgs.slot.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.velocity.rgs.slot.feature.pickcollect.PickCollectFeatureView;
import com.velocity.rgs.slot.feature.respin.RespinFeatureView;
import com.velocity.rgs.slot.math.domain.BonusBuyType;
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
        int remainingFreeSpins,
        Map<String, Object> featureInitPayload,
        PickCollectFeatureView activeFeatureView,
        RespinFeatureView respinView,
        List<GameCommand> availableActions
) {
}
