package com.velocity.rgs.game.feature.pickcollect;

import com.velocity.rgs.math.config.PickCollectCompletion;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Mutable in-memory Pick &amp; Collect feature payload. Persisted in {@code game_session.active_feature_payload}
 * (server-only) and projected via {@code activeFeatureView} for the client (which never sees unrevealed tiles).
 *
 * <p>Semantics:
 * <ul>
 *   <li>{@code currentCollected} accumulates {@code CREDITS} tile values (as bet multipliers)</li>
 *   <li>{@code MULTIPLIER} tiles multiply {@code currentCollected} in place</li>
 *   <li>{@code COLLECT} tiles bank {@code currentCollected} into {@code totalFeatureWin} and reset {@code currentCollected}</li>
 *   <li>{@code BLANK} is a no-op</li>
 *   <li>{@code END} ends the feature immediately</li>
 * </ul>
 * On completion the final feature win = {@code totalFeatureWin + currentCollected} (multiplied by the bet).
 */
public final class PickCollectState {

    public enum Status { ACTIVE, COMPLETED }

    private final List<PickCollectTile> tiles;
    private final LinkedHashSet<Integer> openedPositions = new LinkedHashSet<>();
    private final List<RevealedPick> revealedPicks = new ArrayList<>();

    private BigDecimal currentCollected;
    private BigDecimal totalFeatureWin;
    private int remainingPicks;
    private Status status;
    private final BigDecimal betSize;
    private final PickCollectCompletion completion;

    public PickCollectState(List<PickCollectTile> tiles,
                            BigDecimal betSize,
                            PickCollectCompletion completion,
                            int initialRemainingPicks) {
        this.tiles = List.copyOf(Objects.requireNonNull(tiles, "tiles"));
        this.betSize = Objects.requireNonNull(betSize, "betSize");
        this.completion = Objects.requireNonNull(completion, "completion");
        this.currentCollected = BigDecimal.ZERO;
        this.totalFeatureWin = BigDecimal.ZERO;
        this.remainingPicks = initialRemainingPicks;
        this.status = Status.ACTIVE;
    }

    public List<PickCollectTile> tiles() { return tiles; }
    public Set<Integer> openedPositions() { return Collections.unmodifiableSet(openedPositions); }
    public List<RevealedPick> revealedPicks() { return Collections.unmodifiableList(revealedPicks); }
    public BigDecimal currentCollected() { return currentCollected; }
    public BigDecimal totalFeatureWin() { return totalFeatureWin; }
    public int remainingPicks() { return remainingPicks; }
    public Status status() { return status; }
    public BigDecimal betSize() { return betSize; }
    public PickCollectCompletion completion() { return completion; }
    public int boardSize() { return tiles.size(); }

    public void open(int position) { openedPositions.add(position); }
    public void recordReveal(RevealedPick reveal) { revealedPicks.add(reveal); }
    public void setCurrentCollected(BigDecimal value) { this.currentCollected = value; }
    public void setTotalFeatureWin(BigDecimal value) { this.totalFeatureWin = value; }
    public void decrementRemainingPicks() { if (remainingPicks > 0) remainingPicks--; }
    public void markCompleted() { this.status = Status.COMPLETED; }

    /** Player-visible reveal log entry (no hidden tiles disclosed). */
    public record RevealedPick(int position, com.velocity.rgs.math.domain.PickTileType type, BigDecimal value) {
    }
}
