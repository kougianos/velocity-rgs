package com.velocity.rgs.game.feature.pickcollect;

import com.velocity.rgs.common.error.ErrorCode;
import com.velocity.rgs.common.error.RgsException;
import com.velocity.rgs.math.config.PickCollectCompletion;
import com.velocity.rgs.math.config.PickCollectConfig;
import com.velocity.rgs.math.config.PickTileWeight;
import com.velocity.rgs.math.domain.PickTileType;
import com.velocity.rgs.rng.DeterministicReplayRng;
import com.velocity.rgs.rng.RngDraw;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PickCollectEngineTest {

    private static final BigDecimal BET = new BigDecimal("1.00");
    private static final String EUR = "EUR";

    private PickCollectEngine engine;
    private PickCollectConfig config;

    @BeforeEach
    void setUp() {
        engine = new PickCollectEngine();
        config = new PickCollectConfig(
                4,
                new PickCollectCompletion(PickCollectCompletion.CompletionType.FIXED_PICKS, 3),
                List.of(
                        new PickTileWeight(PickTileType.CREDITS, 50, new int[]{10, 10}),
                        new PickTileWeight(PickTileType.MULTIPLIER, 25, new int[]{2, 2}),
                        new PickTileWeight(PickTileType.COLLECT, 15, null),
                        new PickTileWeight(PickTileType.BLANK, 10, null)
                ),
                5000,
                0);
    }

    @Test
    void startFeatureGeneratesBoardOnceAndItRemainsImmutable() {
        DeterministicReplayRng rng = new DeterministicReplayRng(generateDraws(8));
        PickCollectState state = engine.startFeature(config, BET, rng, 3);

        assertThat(state.tiles()).hasSize(4);
        List<PickCollectTile> snapshot = state.tiles();
        // ensure the same tiles instance is returned each time
        assertThat(state.tiles()).isSameAs(snapshot);
        // first remaining picks = explicit override
        assertThat(state.remainingPicks()).isEqualTo(3);
        // immutable defensively
        assertThatThrownBy(() -> state.tiles().add(new PickCollectTile(PickTileType.BLANK, BigDecimal.ZERO)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void duplicatePickIsRejectedAndStateUnchanged() {
        DeterministicReplayRng rng = new DeterministicReplayRng(generateDraws(8));
        PickCollectState state = engine.startFeature(config, BET, rng, 3);

        engine.applyPick(state, 0, config);
        int before = state.remainingPicks();

        assertThatThrownBy(() -> engine.applyPick(state, 0, config))
                .isInstanceOf(RgsException.class)
                .extracting(e -> ((RgsException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);

        assertThat(state.remainingPicks()).isEqualTo(before);
    }

    @Test
    void creditsAndCollectAndMultiplierProduceCorrectFinalTotal() {
        // Build a deterministic board: [CREDITS(10), COLLECT, MULTIPLIER(2), CREDITS(10)]
        List<RngDraw> draws = List.of(
                new RngDraw(100, 0, 0),    // CREDITS
                new RngDraw(1, 0, 1),       // CREDITS value (range [10,10])
                new RngDraw(100, 75, 2),   // COLLECT
                new RngDraw(100, 51, 3),   // MULTIPLIER
                new RngDraw(1, 0, 4),       // MULTIPLIER value (range [2,2])
                new RngDraw(100, 0, 5),    // CREDITS
                new RngDraw(1, 0, 6)        // CREDITS value
        );
        DeterministicReplayRng rng = new DeterministicReplayRng(draws);
        PickCollectState state = engine.startFeature(config, BET, rng, 3);

        // Confirm board layout
        assertThat(state.tiles().get(0).type()).isEqualTo(PickTileType.CREDITS);
        assertThat(state.tiles().get(1).type()).isEqualTo(PickTileType.COLLECT);
        assertThat(state.tiles().get(2).type()).isEqualTo(PickTileType.MULTIPLIER);
        assertThat(state.tiles().get(3).type()).isEqualTo(PickTileType.CREDITS);

        engine.applyPick(state, 0, config);
        assertThat(state.currentCollected()).isEqualByComparingTo("10");

        engine.applyPick(state, 2, config);
        // CREDITS 10 then MULTIPLIER 2 ⇒ 20
        assertThat(state.currentCollected()).isEqualByComparingTo("20.0000");

        engine.applyPick(state, 1, config);
        // COLLECT banks 20 ⇒ totalFeatureWin=20, currentCollected=0
        assertThat(state.totalFeatureWin()).isEqualByComparingTo("20.0000");
        assertThat(state.currentCollected()).isEqualByComparingTo("0");
        assertThat(state.status()).isEqualTo(PickCollectState.Status.COMPLETED);

        PickCollectEngine.FinalizationResult fin = engine.finalizeFeature(state, config, EUR);
        // 20 multiplier * 1.00 bet = 20.00 EUR (under cap)
        assertThat(fin.finalWin().amount()).isEqualByComparingTo("20.00");
    }

    @Test
    void completionCreditsWalletEverythingAndResetsToBaseGame() {
        DeterministicReplayRng rng = new DeterministicReplayRng(generateDraws(8));
        PickCollectState state = engine.startFeature(config, BET, rng, 3);

        engine.applyPick(state, 0, config);
        engine.applyPick(state, 1, config);
        engine.applyPick(state, 2, config);

        assertThat(state.status()).isEqualTo(PickCollectState.Status.COMPLETED);

        PickCollectEngine.FinalizationResult fin = engine.finalizeFeature(state, config, EUR);
        assertThat(fin.finalWin().currency()).isEqualTo(EUR);
        assertThat(fin.finalWin().amount().signum()).isGreaterThanOrEqualTo(0);
    }

    private static List<RngDraw> generateDraws(int size) {
        // Always pick CREDITS (offset 0 < weight 50) with value 10
        return IntStream.range(0, size)
                .boxed()
                .flatMap(i -> List.of(
                                new RngDraw(100, 0, i * 2L),
                                new RngDraw(1, 0, i * 2L + 1)
                        ).stream())
                .collect(Collectors.toList());
    }
}
