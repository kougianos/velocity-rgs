package com.velocity.rgs.slot.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.velocity.rgs.slot.feature.respin.RespinFeatureView;
import com.velocity.rgs.slot.math.engine.WinLine;
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
        List<CascadeStepView> cascadeSteps,
        RespinFeatureView respinView,
        FeaturesTriggered featuresTriggered,
        SessionStateView sessionState,
        List<GameCommand> availableActions
) {

    /**
     * One drop of a tumbling round, in play order, so the client can animate the sequence instead of
     * snapping to the final board. Present only when the round actually cascaded - a conventional spin
     * omits the field entirely and {@code matrix} alone is the whole story.
     *
     * <p>{@code matrix} on the enclosing response stays the <em>opening</em> board: it is what every
     * existing client draws first, and the steps then play forward from it.
     *
     * @param clearedPositions {@code [row, col]} pairs this drop removes before the next one refills
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Builder
    public record CascadeStepView(
            int index,
            int[][] matrix,
            List<WinLine> winLines,
            BigDecimal multiplier,
            BigDecimal stepWin,
            int[][] clearedPositions
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Builder
    public record FeaturesTriggered(
            int freeSpinsAwarded,
            boolean isPowerBetActive,
            boolean pickCollectTriggered,
            boolean bonusBuyExecuted,
            boolean respinTriggered,
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
