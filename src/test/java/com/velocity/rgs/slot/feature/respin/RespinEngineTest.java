package com.velocity.rgs.slot.feature.respin;

import com.velocity.rgs.rng.RandomNumberGenerator;
import com.velocity.rgs.rng.RngDrawSink;
import com.velocity.rgs.rng.SecureRandomNumberGenerator;
import com.velocity.rgs.slot.math.config.RespinConfig;
import com.velocity.rgs.slot.math.config.SlotMathDefinition;
import com.velocity.rgs.slot.math.config.SlotMathLoader;
import com.velocity.rgs.slot.math.domain.ReelStripSet;
import com.velocity.rgs.slot.math.engine.GridGenerationEngine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Hold &amp; Spin: the trigger, the reset-on-catch counter, the full-grid jackpot, and that locked coins
 * genuinely never move.
 */
class RespinEngineTest {

    private static final BigDecimal BET = new BigDecimal("1.00");
    private final GridGenerationEngine gridEngine = new GridGenerationEngine();
    private final RespinEngine engine = new RespinEngine(gridEngine);

    private SlotMathDefinition math() {
        SlotMathDefinition math = new SlotMathLoader().load("dragon-hoard", "v1").math();
        assertThat(math.respins().enabled())
                .as("dragon-hoard is the fixture for this suite; it must offer Hold & Spin")
                .isTrue();
        return math;
    }

    private RandomNumberGenerator rng() {
        return new SecureRandomNumberGenerator(RngDrawSink.inMemory());
    }

    /** A grid holding coins at the given {@code [row, col]} positions and filler everywhere else. */
    private int[][] gridWithCoins(SlotMathDefinition math, int... rowColPairs) {
        int coin = math.respins().coinSymbolId();
        int[][] grid = new int[math.grid().rows()][math.grid().cols()];
        for (int[] row : grid) {
            java.util.Arrays.fill(row, 1);   // ACE - never the coin
        }
        for (int i = 0; i < rowColPairs.length; i += 2) {
            grid[rowColPairs[i]][rowColPairs[i + 1]] = coin;
        }
        return grid;
    }

    // ---------------------------------------------------------------- trigger

    @Test
    void theFeatureTriggersOnlyAtTheConfiguredCoinCount() {
        SlotMathDefinition math = math();
        RespinConfig config = math.respins();
        int minimum = config.triggerMinCount();

        int[] justUnder = new int[(minimum - 1) * 2];
        for (int i = 0; i < minimum - 1; i++) {
            justUnder[i * 2] = i % math.grid().rows();
            justUnder[i * 2 + 1] = i % math.grid().cols();
        }
        assertThat(engine.triggers(gridWithCoins(math, justUnder), config))
                .as("%d coins is one short of the %d needed", minimum - 1, minimum)
                .isFalse();

        assertThat(engine.triggers(gridWithCoins(math, 0, 0, 1, 1, 2, 2, 0, 3, 1, 4), config))
                .as("%d coins triggers", minimum)
                .isTrue();
    }

    @Test
    void aDisabledConfigNeverTriggers() {
        SlotMathDefinition math = math();
        int[][] fullOfCoins = gridWithCoins(math,
                0, 0, 0, 1, 0, 2, 0, 3, 0, 4, 1, 0, 1, 1, 1, 2, 1, 3, 1, 4);
        assertThat(engine.triggers(fullOfCoins, RespinConfig.disabled())).isFalse();
        assertThat(engine.countCoins(fullOfCoins, RespinConfig.disabled())).isZero();
    }

    @Test
    void theOpeningStateLocksEveryTriggeringCoinAndAwardsTheConfiguredRespins() {
        SlotMathDefinition math = math();
        RespinState state = engine.start(gridWithCoins(math, 0, 0, 1, 1, 2, 2, 0, 3, 1, 4),
                math.respins(), rng());

        assertThat(state.coinCount()).isEqualTo(5);
        assertThat(state.remainingRespins()).isEqualTo(math.respins().respinsAwarded());
        assertThat(state.completed()).isFalse();
        assertThat(state.coinTotal()).isGreaterThan(BigDecimal.ZERO);
        assertThat(state.coins()).allSatisfy(coin ->
                assertThat(coin.value()).as("every coin draws a value from the ladder")
                        .isGreaterThan(BigDecimal.ZERO));
    }

    // ---------------------------------------------------------------- reset-on-catch

    @Test
    void catchingACoinRefillsTheCounterAndCatchingNothingBurnsOne() {
        int awarded = 3;
        RespinState state = RespinState.opening(List.of(
                new RespinState.Coin(0, 0, BigDecimal.ONE)), awarded);

        RespinState missed = state.withNewCoins(List.of(), awarded);
        assertThat(missed.remainingRespins()).isEqualTo(2);
        assertThat(missed.coinCount()).isEqualTo(1);

        RespinState caught = missed.withNewCoins(
                List.of(new RespinState.Coin(1, 1, new BigDecimal("5"))), awarded);
        assertThat(caught.remainingRespins())
                .as("a catch resets the counter to the full award, not merely +1")
                .isEqualTo(awarded);
        assertThat(caught.coinCount()).isEqualTo(2);
        assertThat(caught.coinTotal()).isEqualByComparingTo("6");
    }

    @Test
    void lockedCoinsAreNeverReDrawnAndTheirValuesNeverChange() {
        SlotMathDefinition math = math();
        RandomNumberGenerator rng = rng();
        RespinState state = engine.start(gridWithCoins(math, 0, 0, 1, 1, 2, 2, 0, 3, 1, 4),
                math.respins(), rng);
        List<RespinState.Coin> opening = new ArrayList<>(state.coins());

        for (int i = 0; i < 20 && !state.completed(); i++) {
            RespinEngine.RespinOutcome outcome = engine.respin(state, math, ReelStripSet.BASE, rng);
            for (RespinState.Coin coin : opening) {
                assertThat(outcome.matrix()[coin.row()][coin.col()])
                        .as("a locked coin stays on screen through every respin")
                        .isEqualTo(math.respins().coinSymbolId());
            }
            assertThat(outcome.state().coins().subList(0, opening.size()))
                    .as("the opening coins keep their positions and values verbatim")
                    .isEqualTo(opening);
            state = outcome.state();
            if (outcome.finished()) {
                break;
            }
        }
    }

    @Test
    void aFeatureThatCatchesNothingEndsAfterExactlyTheAwardedRespins() {
        SlotMathDefinition math = math();
        RandomNumberGenerator rng = rng();
        RespinState state = engine.start(gridWithCoins(math, 0, 0, 1, 1, 2, 2, 0, 3, 1, 4),
                math.respins(), rng);

        int respins = 0;
        RespinEngine.RespinOutcome outcome;
        int caught = 0;
        do {
            outcome = engine.respin(state, math, ReelStripSet.BASE, rng);
            caught += outcome.newCoins();
            state = outcome.state();
            respins++;
        } while (!outcome.finished() && respins < 500);

        assertThat(outcome.finished()).isTrue();
        if (caught == 0) {
            assertThat(respins)
                    .as("with no catches the feature lasts exactly the awarded respins")
                    .isEqualTo(math.respins().respinsAwarded());
        } else {
            assertThat(respins)
                    .as("every catch bought more respins, so it ran longer than the base award")
                    .isGreaterThan(0);
        }
    }

    // ---------------------------------------------------------------- jackpots

    @Test
    void aFullGridEndsTheFeatureImmediatelyAndPaysTheTopTier() {
        SlotMathDefinition math = math();
        int rows = math.grid().rows();
        int cols = math.grid().cols();

        List<RespinState.Coin> everyCell = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                everyCell.add(new RespinState.Coin(r, c, BigDecimal.ONE));
            }
        }
        RespinState full = RespinState.opening(everyCell, math.respins().respinsAwarded());
        assertThat(full.gridFull(rows, cols)).isTrue();

        RespinEngine.Settlement settlement = engine.settle(full, math, BET, "EUR");

        assertThat(settlement.jackpotTier())
                .as("a full %dx%d grid earns the tier configured at %d coins", rows, cols, rows * cols)
                .isEqualTo("GRAND");
        assertThat(settlement.reasonCodes())
                .contains(RespinEngine.REASON_SETTLED, RespinEngine.REASON_JACKPOT_PREFIX + "GRAND");
        // 15 coins at 1x each, plus the 2,000x GRAND.
        assertThat(settlement.win().amount()).isEqualByComparingTo("2015.00");
        assertThat(settlement.state().completed()).isTrue();
    }

    @Test
    void theHighestEarnedTierIsAwardedAndTooFewCoinsEarnNone() {
        SlotMathDefinition math = math();
        RespinConfig config = math.respins();

        assertThat(config.jackpotFor(5)).as("below the lowest tier").isNull();
        assertThat(config.jackpotFor(6).tier()).isEqualTo("MINI");
        assertThat(config.jackpotFor(8).tier()).as("between tiers keeps the lower one").isEqualTo("MINI");
        assertThat(config.jackpotFor(9).tier()).isEqualTo("MINOR");
        assertThat(config.jackpotFor(12).tier()).isEqualTo("MAJOR");
        assertThat(config.jackpotFor(15).tier()).isEqualTo("GRAND");
    }

    @Test
    void settlementIsCappedByTheRoundCeiling() {
        SlotMathDefinition math = math();
        int cap = math.limits().maxWinPerRoundMultiplier();

        List<RespinState.Coin> absurd = List.of(
                new RespinState.Coin(0, 0, BigDecimal.valueOf(cap * 10L)));
        RespinEngine.Settlement settlement = engine.settle(
                RespinState.opening(absurd, 3), math, BET, "EUR");

        assertThat(settlement.win().amount()).isEqualByComparingTo(BigDecimal.valueOf(cap));
        assertThat(settlement.reasonCodes()).contains("MAX_WIN_CAPPED");
    }

    // ---------------------------------------------------------------- payload round-trip

    /**
     * Regression guard. The encoded payload is handed to {@code SessionState.RespinAwaiting}, whose
     * {@code Map.copyOf} throws on a null <em>value</em> - so writing the un-earned jackpot tier as
     * null failed the very spin that triggered the feature. Because that is roughly one spin in 580,
     * every unit test that built a {@link RespinState} directly passed while the live path 500'd.
     */
    @Test
    void anEncodedPayloadNeverContainsANullValue() {
        RespinPayloadCodec codec = new RespinPayloadCodec();
        RespinState live = RespinState.opening(
                List.of(new RespinState.Coin(0, 0, BigDecimal.ONE)), 3);
        assertThat(live.jackpotTier()).as("a running feature has earned no tier yet").isNull();

        Map<String, Object> payload = codec.encode(live);

        assertThat(payload).doesNotContainValue(null);
        // The real contract: the sealed state's defensive copy must accept it.
        assertThatCode(() -> Map.copyOf(payload)).doesNotThrowAnyException();
    }

    @Test
    void aStateSurvivesTheRoundTripThroughTheSessionPayload() {
        RespinPayloadCodec codec = new RespinPayloadCodec();
        RespinState original = new RespinState(2, List.of(
                new RespinState.Coin(0, 1, new BigDecimal("5")),
                new RespinState.Coin(2, 4, new BigDecimal("25"))), "MAJOR", true);

        RespinState restored = codec.decode(codec.encode(original));

        assertThat(restored.remainingRespins()).isEqualTo(2);
        assertThat(restored.coins()).isEqualTo(original.coins());
        assertThat(restored.coinTotal()).isEqualByComparingTo("30");
        assertThat(restored.jackpotTier()).isEqualTo("MAJOR");
        assertThat(restored.completed()).isTrue();
    }

    // ---------------------------------------------------------------- replayability

    @Test
    void everyRespinDrawIsCapturedInTheRoundSink() {
        SlotMathDefinition math = math();
        RngDrawSink sink = RngDrawSink.inMemory();
        RandomNumberGenerator rng = new SecureRandomNumberGenerator(sink);

        int[][] trigger = gridWithCoins(math, 0, 0, 1, 1, 2, 2, 0, 3, 1, 4);
        RespinState state = engine.start(trigger, math.respins(), rng);
        int afterStart = sink.drawn().size();
        assertThat(afterStart)
                .as("one value draw per triggering coin")
                .isEqualTo(5);

        RespinEngine.RespinOutcome outcome = engine.respin(state, math, ReelStripSet.BASE, rng);
        int cells = math.grid().rows() * math.grid().cols();
        assertThat(sink.drawn())
                .as("one draw per re-drawn cell, plus one value draw per newly caught coin")
                .hasSize(afterStart + (cells - 5) + outcome.newCoins());
    }
}
