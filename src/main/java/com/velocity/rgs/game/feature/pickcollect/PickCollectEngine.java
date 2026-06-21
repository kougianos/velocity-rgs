package com.velocity.rgs.game.feature.pickcollect;

import com.velocity.rgs.common.error.ErrorCode;
import com.velocity.rgs.common.error.RgsException;
import com.velocity.rgs.common.money.Money;
import com.velocity.rgs.math.config.PickCollectCompletion;
import com.velocity.rgs.math.config.PickCollectConfig;
import com.velocity.rgs.math.config.PickTileWeight;
import com.velocity.rgs.math.domain.PickTileType;
import com.velocity.rgs.rng.RandomNumberGenerator;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure Pick &amp; Collect resolver (Section 5 — Implementation Notes / M5 Task 5.7).
 *
 * <p>Three operations:
 * <ul>
 *   <li>{@link #startFeature(PickCollectConfig, BigDecimal, RandomNumberGenerator, int)} —
 *       generates the immutable board from the weighted tile distribution using the supplied RNG; the
 *       resulting {@link PickCollectState} is the frozen feature payload persisted in
 *       {@code pick_collect_snapshot.board}.</li>
 *   <li>{@link #applyPick(PickCollectState, int, PickCollectConfig)} — validates the position, resolves
 *       the tile, updates accumulators, decrements picks, marks completion when criteria are met.</li>
 *   <li>{@link #finalizeFeature(PickCollectState, PickCollectConfig, String)} — computes the final
 *       feature payout ({@code (totalFeatureWin + currentCollected) * betSize}) capped by
 *       {@code maxFeatureWinMultiplier}, returns {@link FinalizationResult} with reason codes.</li>
 * </ul>
 */
@Component
public class PickCollectEngine {

    public PickCollectState startFeature(PickCollectConfig config,
                                         BigDecimal betSize,
                                         RandomNumberGenerator rng,
                                         int initialRemainingPicks) {
        if (betSize == null || betSize.signum() <= 0) {
            throw new IllegalArgumentException("betSize must be > 0");
        }
        int boardSize = config.boardSize();
        int totalWeight = config.tileDistribution().stream().mapToInt(PickTileWeight::weight).sum();
        if (totalWeight <= 0) {
            throw new IllegalStateException("pickCollect.tileDistribution total weight must be > 0");
        }

        List<PickCollectTile> tiles = new ArrayList<>(boardSize);
        for (int i = 0; i < boardSize; i++) {
            int pick = rng.nextIndex(totalWeight);
            PickTileWeight selected = selectByWeight(config.tileDistribution(), pick);
            BigDecimal value = resolveTileValue(selected, rng);
            tiles.add(new PickCollectTile(selected.type(), value));
        }

        int picks = initialRemainingPicks > 0 ? initialRemainingPicks
                : resolveInitialPicks(config.completion(), boardSize);
        return new PickCollectState(tiles, betSize, config.completion(), picks);
    }

    public PickResolution applyPick(PickCollectState state, int position, PickCollectConfig config) {
        if (state.status() == PickCollectState.Status.COMPLETED) {
            throw new RgsException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "Pick & Collect feature is already completed");
        }
        if (position < 0 || position >= state.boardSize()) {
            throw new RgsException(ErrorCode.VALIDATION_ERROR,
                    "Pick position " + position + " out of range [0," + state.boardSize() + ")");
        }
        if (state.openedPositions().contains(position)) {
            throw new RgsException(ErrorCode.VALIDATION_ERROR,
                    "Pick position " + position + " already opened");
        }
        if (state.remainingPicks() <= 0) {
            throw new RgsException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "No remaining picks");
        }

        PickCollectTile tile = state.tiles().get(position);
        state.open(position);
        state.decrementRemainingPicks();

        List<String> reasons = new ArrayList<>();
        switch (tile.type()) {
            case CREDITS -> state.setCurrentCollected(state.currentCollected().add(tile.value()));
            case MULTIPLIER -> {
                BigDecimal newCollected = state.currentCollected().multiply(tile.value());
                state.setCurrentCollected(newCollected.setScale(4, RoundingMode.HALF_UP));
            }
            case COLLECT -> {
                state.setTotalFeatureWin(state.totalFeatureWin().add(state.currentCollected()));
                state.setCurrentCollected(BigDecimal.ZERO);
                reasons.add("COLLECT_BANKED");
            }
            case BLANK -> { /* no-op */ }
            case END -> {
                // Hitting END ends the feature and forfeits the unbanked pot — only amounts
                // already banked via a COLLECT tile (totalFeatureWin) survive. This is what makes
                // COLLECT a genuine bank-vs-risk decision (classic hold-&-win semantics).
                state.setCurrentCollected(BigDecimal.ZERO);
                state.markCompleted();
                reasons.add("END_TILE_REVEALED");
            }
        }
        state.recordReveal(new PickCollectState.RevealedPick(position, tile.type(), tile.value()));

        if (state.status() != PickCollectState.Status.COMPLETED && isCompleted(state, config)) {
            // Board exhausted (or fixed-pick / threshold rule met) without hitting END: the player
            // keeps whatever is currently unbanked, so currentCollected is NOT forfeited here.
            state.markCompleted();
            reasons.add("PICK_COMPLETED");
        }
        return new PickResolution(tile.type(), tile.value(), reasons);
    }

    public FinalizationResult finalizeFeature(PickCollectState state, PickCollectConfig config, String currency) {
        if (state.status() != PickCollectState.Status.COMPLETED) {
            throw new RgsException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "Pick & Collect feature is not yet completed");
        }
        BigDecimal grossMultiplier = state.totalFeatureWin().add(state.currentCollected());
        BigDecimal raw = grossMultiplier.multiply(state.betSize());
        List<String> reasons = new ArrayList<>();
        BigDecimal cap = state.betSize().multiply(BigDecimal.valueOf(config.maxFeatureWinMultiplier()));
        BigDecimal capped = raw;
        if (raw.compareTo(cap) > 0) {
            capped = cap;
            reasons.add("MAX_WIN_CAPPED");
        }
        Money finalWin = Money.of(capped.setScale(Money.minorUnitScale(currency), RoundingMode.HALF_UP), currency);
        return new FinalizationResult(finalWin, reasons);
    }

    private PickTileWeight selectByWeight(List<PickTileWeight> distribution, int pick) {
        int cursor = 0;
        for (PickTileWeight w : distribution) {
            cursor += w.weight();
            if (pick < cursor) {
                return w;
            }
        }
        return distribution.get(distribution.size() - 1);
    }

    private BigDecimal resolveTileValue(PickTileWeight weight, RandomNumberGenerator rng) {
        if (weight.type() != PickTileType.CREDITS && weight.type() != PickTileType.MULTIPLIER) {
            return BigDecimal.ZERO;
        }
        int[] range = weight.valueRange();
        int span = range[1] - range[0] + 1;
        int rolled = range[0] + rng.nextIndex(span);
        return BigDecimal.valueOf(rolled);
    }

    private boolean isCompleted(PickCollectState state, PickCollectConfig config) {
        PickCollectCompletion completion = config.completion();
        return switch (completion.type()) {
            // END_TILE ends inline when an END is revealed; this is the board-exhaustion fallback
            // (every tile opened without hitting END) so the feature always terminates.
            case FIXED_PICKS, END_TILE -> state.remainingPicks() <= 0;
            case COLLECT_THRESHOLD -> {
                BigDecimal threshold = BigDecimal.valueOf(completion.value());
                yield state.totalFeatureWin().add(state.currentCollected()).compareTo(threshold) >= 0;
            }
        };
    }

    private int resolveInitialPicks(PickCollectCompletion completion, int boardSize) {
        // END_TILE has no fixed pick count — the player may open every tile until an END appears,
        // so the natural ceiling is the board size (also yields a sane "picks left" countdown).
        if (completion.type() == PickCollectCompletion.CompletionType.FIXED_PICKS) {
            return completion.value();
        }
        return boardSize;
    }

    /** Result of a single {@link #applyPick} call. */
    public record PickResolution(PickTileType resolvedTileType, BigDecimal resolvedValue, List<String> reasonCodes) {
        public PickResolution {
            reasonCodes = List.copyOf(reasonCodes);
        }
    }

    /** Result of {@link #finalizeFeature}. */
    public record FinalizationResult(Money finalWin, List<String> reasonCodes) {
        public FinalizationResult {
            reasonCodes = List.copyOf(reasonCodes);
        }
    }
}
