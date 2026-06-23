package com.velocity.rgs.slot.feature.pickcollect;

import com.velocity.rgs.slot.feature.pickcollect.PickCollectState.RevealedPick;
import com.velocity.rgs.slot.feature.pickcollect.PickCollectState.Status;

import java.math.BigDecimal;
import java.util.List;

/**
 * Client-facing projection of {@link PickCollectState} per A.7 and the Pick &amp; Collect Implementation
 * Notes. Exposes board size, opened positions, revealed values, current accumulators, and status —
 * but NEVER the unrevealed tile contents.
 */
public record PickCollectFeatureView(
        int boardSize,
        List<Integer> openedPositions,
        List<RevealedPick> revealedPicks,
        BigDecimal currentCollected,
        BigDecimal totalFeatureWin,
        int remainingPicks,
        Status status
) {

    public static PickCollectFeatureView of(PickCollectState state) {
        return new PickCollectFeatureView(
                state.boardSize(),
                List.copyOf(state.openedPositions()),
                state.revealedPicks(),
                state.currentCollected(),
                state.totalFeatureWin(),
                state.remainingPicks(),
                state.status()
        );
    }
}
