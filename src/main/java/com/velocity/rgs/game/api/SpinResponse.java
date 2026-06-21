package com.velocity.rgs.game.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.velocity.rgs.math.engine.WinLine;
import com.velocity.rgs.session.domain.GameCommand;
import com.velocity.rgs.session.domain.GameState;
import lombok.Builder;

import java.math.BigDecimal;
import java.util.List;

/**
 * Response body for {@code POST /api/v1/slot/spin} (A.7).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Builder
public record SpinResponse(
        String sessionId,
        long sessionVersion,
        String roundId,
        String mathVersion,
        BigDecimal betDebited,
        BigDecimal totalWin,
        int[][] matrix,
        int[] stopPositions,
        List<WinLine> winLines,
        FeaturesTriggered featuresTriggered,
        SessionStateView sessionState,
        List<GameCommand> availableActions
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Builder
    public record FeaturesTriggered(
            int freeSpinsAwarded,
            boolean isPowerBetActive,
            boolean pickCollectTriggered,
            boolean bonusBuyExecuted,
            List<String> reasonCodes
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Builder
    public record SessionStateView(
            GameState currentState,
            int remainingFreeSpins,
            BigDecimal accumulatedFreeSpinsWin
    ) {
    }
}
