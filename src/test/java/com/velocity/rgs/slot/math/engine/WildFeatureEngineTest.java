package com.velocity.rgs.slot.math.engine;

import com.velocity.rgs.slot.math.config.SlotMathDefinition;
import com.velocity.rgs.slot.math.config.SlotMathLoader;
import com.velocity.rgs.slot.math.config.WildFeatureConfig;
import com.velocity.rgs.slot.math.domain.ReelStripSet;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Expanding, sticky and walking wilds - the config-driven grid transforms of §1.4. */
class WildFeatureEngineTest {

    private final WildFeatureEngine engine = new WildFeatureEngine();

    /** aztec-fire's symbol set: WILD is 9. Its own wildFeatures block is irrelevant here. */
    private SlotMathDefinition mathWith(WildFeatureConfig wilds) {
        SlotMathDefinition m = new SlotMathLoader().load("aztec-fire", "v1").math();
        return new SlotMathDefinition(m.gameId(), m.mathVersion(), m.targetRtp(), m.grid(),
                m.winModel(), m.waysDirection(), wilds, m.symbols(), m.paylines(), m.payTable(),
                m.reelStrips(), m.scatterTriggers(), m.freeSpins(), m.powerBet(), m.bonusBuyOptions(),
                m.pickCollect(), m.cascades(), m.respins(), m.limits(), m.betConfig());
    }

    private static final int WILD = 9;

    private int[][] grid() {
        return new int[][]{
                {1, 2, 3, 4, 5},
                {1, 2, 3, 4, 5},
                {1, 2, 3, 4, 5},
        };
    }

    // ---------------------------------------------------------------- expanding

    @Test
    void anExpandingWildFillsItsWholeReelAndLeavesOthersAlone() {
        SlotMathDefinition math = mathWith(new WildFeatureConfig(true, false, false, 0, Set.of()));
        int[][] grid = grid();
        grid[1][2] = WILD;

        WildFeatureEngine.WildOutcome outcome = engine.apply(grid, math, ReelStripSet.BASE, List.of());

        assertThat(outcome.matrix()[0][2]).isEqualTo(WILD);
        assertThat(outcome.matrix()[1][2]).isEqualTo(WILD);
        assertThat(outcome.matrix()[2][2]).isEqualTo(WILD);
        assertThat(outcome.matrix()[0][1]).as("neighbouring reels are untouched").isEqualTo(2);
        assertThat(outcome.matrix()[0][3]).isEqualTo(4);
        assertThat(outcome.reasonCodes()).contains(WildFeatureEngine.REASON_EXPANDED);
    }

    @Test
    void aBoardWithNoWildIsReturnedUnchanged() {
        SlotMathDefinition math = mathWith(new WildFeatureConfig(true, false, false, 0, Set.of()));
        int[][] grid = grid();

        WildFeatureEngine.WildOutcome outcome = engine.apply(grid, math, ReelStripSet.BASE, List.of());

        assertThat(outcome.matrix()).isDeepEqualTo(grid());
        assertThat(outcome.reasonCodes()).isEmpty();
        assertThat(outcome.carryForward()).isEmpty();
    }

    @Test
    void theTransformNeverMutatesTheGridItWasGiven() {
        SlotMathDefinition math = mathWith(new WildFeatureConfig(true, false, false, 0, Set.of()));
        int[][] grid = grid();
        grid[1][2] = WILD;

        engine.apply(grid, math, ReelStripSet.BASE, List.of());

        assertThat(grid[0][2]).as("the caller's grid is left as drawn").isEqualTo(3);
        assertThat(grid[2][2]).isEqualTo(3);
    }

    // ---------------------------------------------------------------- sticky

    @Test
    void aStickyWildIsCarriedForwardAndStampedBackOntoTheNextBoard() {
        SlotMathDefinition math = mathWith(new WildFeatureConfig(false, true, false, 2, Set.of()));
        int[][] first = grid();
        first[0][3] = WILD;

        WildFeatureEngine.WildOutcome spinOne = engine.apply(first, math, ReelStripSet.BASE, List.of());
        assertThat(spinOne.carryForward())
                .containsExactly(new WildFeatureEngine.WildCell(0, 3, 2));

        WildFeatureEngine.WildOutcome spinTwo = engine.apply(grid(), math, ReelStripSet.BASE,
                spinOne.carryForward());
        assertThat(spinTwo.matrix()[0][3])
                .as("the wild is still there on a board that drew none")
                .isEqualTo(WILD);
        assertThat(spinTwo.reasonCodes()).contains(WildFeatureEngine.REASON_STICKY);
    }

    @Test
    void aStickyWildExpiresAfterItsConfiguredSpins() {
        SlotMathDefinition math = mathWith(new WildFeatureConfig(false, true, false, 1, Set.of()));
        int[][] first = grid();
        first[2][1] = WILD;

        List<WildFeatureEngine.WildCell> carry =
                engine.apply(first, math, ReelStripSet.BASE, List.of()).carryForward();
        assertThat(carry).hasSize(1);

        WildFeatureEngine.WildOutcome spinTwo = engine.apply(grid(), math, ReelStripSet.BASE, carry);
        assertThat(spinTwo.matrix()[2][1]).as("still visible on the spin it survives").isEqualTo(WILD);
        assertThat(spinTwo.carryForward())
                .as("its single sticky spin is spent, so it does not carry again")
                .isEmpty();

        WildFeatureEngine.WildOutcome spinThree =
                engine.apply(grid(), math, ReelStripSet.BASE, spinTwo.carryForward());
        assertThat(spinThree.matrix()[2][1])
                .as("gone - the cell shows whatever the reels drew there")
                .isEqualTo(grid()[2][1]);
    }

    // ---------------------------------------------------------------- walking

    @Test
    void aWalkingWildStepsOneReelLeftEachSpinAndFallsOffTheBoard() {
        SlotMathDefinition math = mathWith(new WildFeatureConfig(false, true, true, 5, Set.of()));
        int[][] first = grid();
        first[1][2] = WILD;

        List<WildFeatureEngine.WildCell> carry =
                engine.apply(first, math, ReelStripSet.BASE, List.of()).carryForward();

        WildFeatureEngine.WildOutcome spinTwo = engine.apply(grid(), math, ReelStripSet.BASE, carry);
        assertThat(spinTwo.matrix()[1][1]).as("walked from reel 2 to reel 1").isEqualTo(WILD);
        assertThat(spinTwo.matrix()[1][2]).as("and vacated reel 2").isEqualTo(grid()[1][2]);
        assertThat(spinTwo.reasonCodes()).contains(WildFeatureEngine.REASON_WALKED);

        WildFeatureEngine.WildOutcome spinThree =
                engine.apply(grid(), math, ReelStripSet.BASE, spinTwo.carryForward());
        assertThat(spinThree.matrix()[1][0]).as("now on reel 0").isEqualTo(WILD);

        WildFeatureEngine.WildOutcome spinFour =
                engine.apply(grid(), math, ReelStripSet.BASE, spinThree.carryForward());
        assertThat(spinFour.matrix()[1][0])
                .as("stepping left from reel 0 walks it off the grid entirely")
                .isEqualTo(grid()[1][0]);
    }

    // ---------------------------------------------------------------- scoping and config

    @Test
    void behavioursOnlyApplyOnTheStripSetsTheyAreScopedTo() {
        SlotMathDefinition math = mathWith(
                new WildFeatureConfig(true, false, false, 0, Set.of(ReelStripSet.FREE_SPINS)));
        int[][] grid = grid();
        grid[1][2] = WILD;

        assertThat(engine.apply(grid, math, ReelStripSet.BASE, List.of()).matrix()[0][2])
                .as("the base game is untouched when the config scopes to free spins")
                .isEqualTo(3);
        assertThat(engine.apply(grid, math, ReelStripSet.FREE_SPINS, List.of()).matrix()[0][2])
                .as("but free spins expand")
                .isEqualTo(WILD);
    }

    @Test
    void anInertConfigIsANoOpOnEveryStripSet() {
        SlotMathDefinition math = mathWith(WildFeatureConfig.none());
        int[][] grid = grid();
        grid[1][2] = WILD;

        for (ReelStripSet stripSet : ReelStripSet.values()) {
            assertThat(engine.apply(grid, math, stripSet, List.of()).matrix())
                    .as("no wild behaviour configured means no transform on %s", stripSet)
                    .isDeepEqualTo(grid);
        }
        assertThat(WildFeatureConfig.none().active()).isFalse();
    }

    @Test
    void aWildCannotWalkWithoutFirstBeingSticky() {
        assertThatThrownBy(() -> new WildFeatureConfig(false, false, true, 3, Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("walking requires sticky");
    }

    @Test
    void stickyBehaviourNeedsAPositiveSpinCount() {
        assertThatThrownBy(() -> new WildFeatureConfig(false, true, false, 0, Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stickySpins");
    }
}
