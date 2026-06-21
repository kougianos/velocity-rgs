package com.velocity.rgs.math.config;

import java.util.List;
import java.util.Objects;

public record PickCollectConfig(
        int boardSize,
        PickCollectCompletion completion,
        List<PickTileWeight> tileDistribution,
        int maxFeatureWinMultiplier
) {

    public PickCollectConfig {
        if (boardSize <= 0) {
            throw new IllegalArgumentException("pickCollect.boardSize must be > 0");
        }
        Objects.requireNonNull(completion, "pickCollect.completion");
        Objects.requireNonNull(tileDistribution, "pickCollect.tileDistribution");
        if (tileDistribution.isEmpty()) {
            throw new IllegalArgumentException("pickCollect.tileDistribution cannot be empty");
        }
        if (maxFeatureWinMultiplier <= 0) {
            throw new IllegalArgumentException("pickCollect.maxFeatureWinMultiplier must be > 0");
        }
        tileDistribution = List.copyOf(tileDistribution);
    }
}
