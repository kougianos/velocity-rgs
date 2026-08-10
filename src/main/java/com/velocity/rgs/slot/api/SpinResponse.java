package com.velocity.rgs.slot.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.velocity.rgs.jackpot.domain.JackpotAward;
import com.velocity.rgs.jackpot.domain.JackpotContribution;
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
        /* What this spin fed the shared progressive pools, and where they stand after it (§2). Absent
           on a game that does not contribute, and on a free spin, which costs nothing and feeds
           nothing - in both cases there is no claim to make and the field is simply not there. */
        JackpotContribution jackpot,
        /* The pool this spin just won, if it won one (§2). Absent on every spin that did not,
           which is nearly all of them - a field that was always present and usually null would
           make the client test for a win on a shape rather than on its existence. */
        JackpotAward jackpotWin,
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
