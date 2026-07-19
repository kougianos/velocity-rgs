package com.velocity.rgs.slot.math.engine;

import com.velocity.rgs.rng.DeterministicReplayRng;
import com.velocity.rgs.rng.RandomNumberGenerator;
import com.velocity.rgs.rng.RngDraw;
import com.velocity.rgs.rng.RngDrawSink;
import com.velocity.rgs.rng.SecureRandomNumberGenerator;
import com.velocity.rgs.slot.math.config.CascadeConfig;
import com.velocity.rgs.slot.math.config.GameDefinition;
import com.velocity.rgs.slot.math.config.SlotMathDefinition;
import com.velocity.rgs.slot.math.config.SlotMathLoader;
import com.velocity.rgs.slot.math.domain.ReelStripSet;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cascading reels: the drop sequence, the progressive multiplier ladder, and - most importantly - that
 * refills draw through the round's own RNG so the whole tumble replays.
 */
class CascadeEngineTest {

    private static final BigDecimal BET = new BigDecimal("1.00");
    private final GridGenerationEngine gridEngine = new GridGenerationEngine();
    private final ReelEvaluator evaluator = new ReelEvaluator();

    private SlotMathDefinition cascadingMath() {
        GameDefinition game = new SlotMathLoader().load("gilded-cascade", "v1");
        assertThat(game.math().cascades().enabled())
                .as("gilded-cascade is the fixture for this suite; it must actually cascade")
                .isTrue();
        return game.math();
    }

    private SlotMathDefinition nonCascadingMath() {
        return new SlotMathLoader().load("aztec-fire", "v1").math();
    }

    // ---------------------------------------------------------------- draw capture

    /**
     * The correctness-critical property of the whole mechanic: a cascading round's refills must draw
     * from the round's RNG, so its sink holds <em>every</em> draw the engine consumed. If refills used
     * a fresh RNG the opening board alone would be captured and the round would be unreplayable, which
     * is exactly the failure this asserts against.
     */
    @Test
    void refillDrawsAreCapturedInTheRoundSink() {
        SlotMathDefinition math = cascadingMath();
        // Search for a round that actually tumbled; ~74% of rounds do, so this lands immediately.
        for (int attempt = 0; attempt < 200; attempt++) {
            RngDrawSink sink = RngDrawSink.inMemory();
            RandomNumberGenerator rng = new SecureRandomNumberGenerator(sink);
            GridGenerationResult opening = gridEngine.generate(math, ReelStripSet.BASE, rng);
            EvaluationResult result = evaluator.evaluateRound(opening.matrix(), opening.stopPositions(),
                    BET, math, ReelStripSet.BASE, rng);
            if (!result.cascaded()) {
                continue;
            }

            int expectedDraws = math.grid().cols();       // the opening board's reel stops
            for (CascadeStep step : result.steps()) {
                expectedDraws += step.clearedPositions().length;   // one draw per refilled cell
            }
            assertThat(sink.drawn())
                    .as("every refill draw must land in the round's sink, in order")
                    .hasSize(expectedDraws);
            return;
        }
        throw new AssertionError("no cascading round observed in 200 attempts");
    }

    /**
     * And the payoff: feeding those captured draws back through the same code path rebuilds the round
     * grid for grid. This is {@link com.velocity.rgs.audit.replay.ReplayService} in miniature, without
     * the database.
     */
    @Test
    void aCascadingRoundReplaysBitExactFromItsDraws() {
        SlotMathDefinition math = cascadingMath();
        for (int attempt = 0; attempt < 200; attempt++) {
            RngDrawSink sink = RngDrawSink.inMemory();
            RandomNumberGenerator rng = new SecureRandomNumberGenerator(sink);
            GridGenerationResult opening = gridEngine.generate(math, ReelStripSet.BASE, rng);
            EvaluationResult original = evaluator.evaluateRound(opening.matrix(),
                    opening.stopPositions(), BET, math, ReelStripSet.BASE, rng);
            if (!original.cascaded()) {
                continue;
            }

            List<RngDraw> draws = new ArrayList<>(sink.drawn());
            DeterministicReplayRng replayRng = new DeterministicReplayRng(draws);
            GridGenerationResult replayedOpening = gridEngine.generate(math, ReelStripSet.BASE, replayRng);
            EvaluationResult replayed = evaluator.evaluateRound(replayedOpening.matrix(),
                    replayedOpening.stopPositions(), BET, math, ReelStripSet.BASE, replayRng);

            assertThat(replayed.steps()).hasSameSizeAs(original.steps());
            for (int i = 0; i < original.steps().size(); i++) {
                assertThat(Arrays.deepEquals(original.steps().get(i).grid(), replayed.steps().get(i).grid()))
                        .as("step %d grid must reconstruct exactly", i)
                        .isTrue();
                assertThat(replayed.steps().get(i).stopPositions())
                        .as("step %d draws must reconstruct exactly", i)
                        .isEqualTo(original.steps().get(i).stopPositions());
            }
            assertThat(replayed.totalWin()).isEqualByComparingTo(original.totalWin());
            return;
        }
        throw new AssertionError("no cascading round observed in 200 attempts");
    }

    // ---------------------------------------------------------------- sequence shape

    @Test
    void aNonCascadingGameProducesExactlyOneStepCarryingItsReelStops() {
        SlotMathDefinition math = nonCascadingMath();
        RandomNumberGenerator rng = new SecureRandomNumberGenerator(RngDrawSink.inMemory());
        GridGenerationResult grid = gridEngine.generate(math, ReelStripSet.BASE, rng);

        EvaluationResult result = evaluator.evaluateRound(grid.matrix(), grid.stopPositions(), BET,
                math, ReelStripSet.BASE, rng);

        assertThat(result.cascaded()).isFalse();
        assertThat(result.steps()).hasSize(1);
        assertThat(result.steps().get(0).stopPositions()).isEqualTo(grid.stopPositions());
        assertThat(result.steps().get(0).grid()).isEqualTo(grid.matrix());
        assertThat(result.steps().get(0).clearedPositions()).isEmpty();
    }

    @Test
    void everyRoundEndsOnANonPayingBoardThatClearsNothing() {
        SlotMathDefinition math = cascadingMath();
        RandomNumberGenerator rng = new SecureRandomNumberGenerator(RngDrawSink.inMemory());
        for (int i = 0; i < 500; i++) {
            GridGenerationResult opening = gridEngine.generate(math, ReelStripSet.BASE, rng);
            EvaluationResult result = evaluator.evaluateRound(opening.matrix(), opening.stopPositions(),
                    BET, math, ReelStripSet.BASE, rng);
            CascadeStep last = result.steps().get(result.steps().size() - 1);

            assertThat(last.clearedPositions())
                    .as("the settled board a player is left looking at clears nothing")
                    .isEmpty();
            assertThat(result.steps().size())
                    .as("a round may not exceed maxCascades refills")
                    .isLessThanOrEqualTo(math.cascades().maxCascades() + 1);
            for (int step = 0; step < result.steps().size() - 1; step++) {
                assertThat(result.steps().get(step).clearedPositions())
                        .as("a step that was refilled must have cleared something")
                        .isNotEmpty();
            }
        }
    }

    @Test
    void stepWinsSumToTheRoundTotal() {
        SlotMathDefinition math = cascadingMath();
        RandomNumberGenerator rng = new SecureRandomNumberGenerator(RngDrawSink.inMemory());
        for (int i = 0; i < 500; i++) {
            GridGenerationResult opening = gridEngine.generate(math, ReelStripSet.BASE, rng);
            EvaluationResult result = evaluator.evaluateRound(opening.matrix(), opening.stopPositions(),
                    BET, math, ReelStripSet.BASE, rng);

            BigDecimal summed = result.steps().stream()
                    .map(CascadeStep::stepWin)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(summed)
                    .as("the round's total is the sum of what its drops paid")
                    .isEqualByComparingTo(result.totalWin());
            assertThat(result.winLines().stream()
                    .map(WinLine::payout)
                    .reduce(BigDecimal.ZERO, BigDecimal::add))
                    .as("the flattened win list is already multiplier-scaled, so it sums to the total")
                    .isEqualByComparingTo(result.totalWin());
        }
    }

    // ---------------------------------------------------------------- multiplier ladder

    @Test
    void eachStepPaysAtItsConfiguredProgressiveMultiplier() {
        SlotMathDefinition math = cascadingMath();
        List<BigDecimal> ladder = math.cascades().stepMultipliers();
        RandomNumberGenerator rng = new SecureRandomNumberGenerator(RngDrawSink.inMemory());

        boolean sawMultiStep = false;
        for (int i = 0; i < 500; i++) {
            GridGenerationResult opening = gridEngine.generate(math, ReelStripSet.BASE, rng);
            EvaluationResult result = evaluator.evaluateRound(opening.matrix(), opening.stopPositions(),
                    BET, math, ReelStripSet.BASE, rng);
            sawMultiStep |= result.cascaded();
            for (CascadeStep step : result.steps()) {
                BigDecimal expected = ladder.get(Math.min(step.index(), ladder.size() - 1));
                assertThat(step.multiplier())
                        .as("step %d must pay at ladder position %d", step.index(), step.index())
                        .isEqualByComparingTo(expected);
            }
        }
        assertThat(sawMultiStep).as("expected at least one tumble in 500 rounds").isTrue();
    }

    @Test
    void theLadderRepeatsItsFinalEntryBeyondTheConfiguredLength() {
        CascadeConfig config = new CascadeConfig(true, 8,
                List.of(BigDecimal.ONE, new BigDecimal("2"), new BigDecimal("3")));

        assertThat(config.multiplierFor(0)).isEqualByComparingTo("1");
        assertThat(config.multiplierFor(2)).isEqualByComparingTo("3");
        assertThat(config.multiplierFor(3)).isEqualByComparingTo("3");
        assertThat(config.multiplierFor(99)).isEqualByComparingTo("3");
        assertThatThrownBy(() -> config.multiplierFor(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aDisabledConfigNeverMultipliesAndRejectsNothing() {
        CascadeConfig disabled = CascadeConfig.disabled();
        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.multiplierFor(0)).isEqualByComparingTo("1");
        assertThat(disabled.multiplierFor(50)).isEqualByComparingTo("1");
    }

    @Test
    void anEnabledConfigMustBoundItsChainAndKeepMultipliersPositive() {
        assertThatThrownBy(() -> new CascadeConfig(true, 0, List.of(BigDecimal.ONE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxCascades");
        assertThatThrownBy(() -> new CascadeConfig(true, 5, List.of(BigDecimal.ZERO)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stepMultipliers");
    }
}
