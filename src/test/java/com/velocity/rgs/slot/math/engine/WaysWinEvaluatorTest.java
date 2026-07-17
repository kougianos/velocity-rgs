package com.velocity.rgs.slot.math.engine;

import com.velocity.rgs.catalog.BetConfig;
import com.velocity.rgs.slot.math.config.FreeSpinsConfig;
import com.velocity.rgs.slot.math.config.Grid;
import com.velocity.rgs.slot.math.config.Limits;
import com.velocity.rgs.slot.math.config.PickCollectCompletion;
import com.velocity.rgs.slot.math.config.PickCollectConfig;
import com.velocity.rgs.slot.math.config.PickTileWeight;
import com.velocity.rgs.slot.math.config.PowerBetConfig;
import com.velocity.rgs.slot.math.config.ScatterTriggers;
import com.velocity.rgs.slot.math.config.SlotMathDefinition;
import com.velocity.rgs.slot.math.domain.PayTable;
import com.velocity.rgs.slot.math.domain.Payline;
import com.velocity.rgs.slot.math.domain.PickTileType;
import com.velocity.rgs.slot.math.domain.ReelStrip;
import com.velocity.rgs.slot.math.domain.ReelStripSet;
import com.velocity.rgs.slot.math.domain.Symbol;
import com.velocity.rgs.slot.math.domain.SymbolType;
import com.velocity.rgs.slot.math.domain.WinModel;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ways-to-win evaluation on a 3x5 grid (243 ways).
 *
 * <p>Bets are 243.00 throughout so that {@code wayBet} is exactly 1.00 and every expected payout reads as
 * {@code coefficient x ways} with no rounding to reason about.
 *
 * <p>{@link #FILLER} is a STANDARD symbol with no pay table entries. It exists so a grid can be padded
 * without the padding forming its own runs - every symbol is live under the ways model, so an inert
 * filler is the only way to isolate the symbol under test.
 */
class WaysWinEvaluatorTest {

    private static final int ACE = 1;
    private static final int KING = 2;
    private static final int QUEEN = 3;
    private static final int FILLER = 5;
    private static final int WILD = 9;
    private static final int SCATTER = 12;

    /** wayBet == 1.00 at this stake, so expected payouts are just coefficient x ways. */
    private static final BigDecimal BET = new BigDecimal("243.00");

    private final ReelEvaluator evaluator = new ReelEvaluator();

    @Test
    void waysAreTheProductOfPerReelHitCounts() {
        // ACE hits reel0 x1, reel1 x2, reel2 x1, then reel3 has none -> 3-reel run, 1*2*1 = 2 ways.
        int[][] matrix = {
                {FILLER, ACE,    FILLER, FILLER, FILLER},
                {ACE,    ACE,    ACE,    FILLER, FILLER},
                {FILLER, FILLER, FILLER, FILLER, FILLER}
        };
        EvaluationResult result = evaluator.evaluate(matrix, BET, math(10_000));

        assertThat(result.winLines()).hasSize(1);
        WinLine win = result.winLines().get(0);
        assertThat(win.symbolId()).isEqualTo(ACE);
        assertThat(win.count()).isEqualTo(3);
        assertThat(win.ways()).isEqualTo(2);
        assertThat(win.lineId()).isNull();
        assertThat(win.payout()).isEqualByComparingTo("10.00");   // coef(ACE,3)=5 x 2 ways
        assertThat(result.totalWin()).isEqualByComparingTo("10.00");
    }

    @Test
    void reelsBeyondTheRunDoNotMultiplyIt() {
        // Same 2-way, 3-reel ACE run as above, but reels 3 and 4 are now full of a different symbol.
        // Ways must stay 2: a 3-reel run is worth c0*c1*c2 regardless of what follows. (Enumerating
        // whole 5-reel paths instead would wrongly report 2 * 3 * 3 = 18.)
        int[][] matrix = {
                {FILLER, ACE,    FILLER, QUEEN, QUEEN},
                {ACE,    ACE,    ACE,    QUEEN, QUEEN},
                {FILLER, FILLER, FILLER, QUEEN, QUEEN}
        };
        EvaluationResult result = evaluator.evaluate(matrix, BET, math(10_000));

        WinLine ace = result.winLines().stream().filter(w -> w.symbolId() == ACE).findFirst().orElseThrow();
        assertThat(ace.ways()).isEqualTo(2);
        assertThat(ace.count()).isEqualTo(3);
        assertThat(ace.payout()).isEqualByComparingTo("10.00");
    }

    @Test
    void fullGridOfOneSymbolPaysEveryWay() {
        int[][] matrix = {
                {ACE, ACE, ACE, ACE, ACE},
                {ACE, ACE, ACE, ACE, ACE},
                {ACE, ACE, ACE, ACE, ACE}
        };
        EvaluationResult result = evaluator.evaluate(matrix, BET, math(1_000_000));

        WinLine win = result.winLines().get(0);
        assertThat(win.ways()).isEqualTo(243);           // 3^5
        assertThat(win.count()).isEqualTo(5);
        assertThat(win.payout()).isEqualByComparingTo("24300.00");   // coef(ACE,5)=100 x 243
    }

    @Test
    void runShorterThanThreeReelsDoesNotPay() {
        int[][] matrix = {
                {FILLER, FILLER, FILLER, FILLER, FILLER},
                {ACE,    ACE,    FILLER, FILLER, FILLER},
                {FILLER, FILLER, FILLER, FILLER, FILLER}
        };
        assertThat(evaluator.evaluate(matrix, BET, math(10_000)).winLines()).isEmpty();
    }

    @Test
    void wildsSubstituteAndCountTowardTheRun() {
        // reel0: ACE + WILD = 2 hits, reel1: 1, reel2: 1 -> 2 ways over 3 reels.
        int[][] matrix = {
                {WILD,   FILLER, FILLER, FILLER, FILLER},
                {ACE,    ACE,    ACE,    FILLER, FILLER},
                {FILLER, FILLER, FILLER, FILLER, FILLER}
        };
        EvaluationResult result = evaluator.evaluate(matrix, BET, math(10_000));

        WinLine ace = result.winLines().stream().filter(w -> w.symbolId() == ACE).findFirst().orElseThrow();
        assertThat(ace.ways()).isEqualTo(2);
        assertThat(ace.payout()).isEqualByComparingTo("10.00");
    }

    @Test
    void wildsDoNotPayAsThemselves() {
        // A full column of wilds on reels 0-2 would, under a naive model, pay as WILD on top of every
        // symbol it substitutes for. Wilds have no pay table entry here, so only real runs pay: ACE
        // continues through reel 3 (3 wilds x 3 wilds x 3 wilds x 1 ace = 27 ways over 4 reels).
        int[][] matrix = {
                {WILD, WILD, WILD, FILLER, FILLER},
                {WILD, WILD, WILD, ACE,    FILLER},
                {WILD, WILD, WILD, FILLER, FILLER}
        };
        EvaluationResult result = evaluator.evaluate(matrix, BET, math(1_000_000));

        assertThat(result.winLines()).noneMatch(w -> w.symbolId() == WILD);
        WinLine ace = result.winLines().stream().filter(w -> w.symbolId() == ACE).findFirst().orElseThrow();
        assertThat(ace.count()).isEqualTo(4);
        assertThat(ace.ways()).isEqualTo(27);
        assertThat(ace.payout()).isEqualByComparingTo("540.00");   // coef(ACE,4)=20 x 27
    }

    @Test
    void severalSymbolsCanWinOnTheSameGrid() {
        // Unlike a payline, every symbol is evaluated independently, so two runs coexist.
        int[][] matrix = {
                {KING,   KING,   KING,   FILLER, FILLER},
                {ACE,    ACE,    ACE,    FILLER, FILLER},
                {FILLER, FILLER, FILLER, FILLER, FILLER}
        };
        EvaluationResult result = evaluator.evaluate(matrix, BET, math(10_000));

        assertThat(result.winLines()).extracting(WinLine::symbolId).containsExactlyInAnyOrder(ACE, KING);
        assertThat(result.totalWin()).isEqualByComparingTo("9.00");   // ACE 5x1 + KING 4x1
    }

    @Test
    void scatterDoesNotCountTowardARun() {
        // reel2 holds a SCATTER where an ACE would have continued the run -> ACE stops at 2 reels.
        int[][] matrix = {
                {FILLER, FILLER, SCATTER, FILLER, FILLER},
                {ACE,    ACE,    FILLER,  FILLER, FILLER},
                {FILLER, FILLER, FILLER,  FILLER, FILLER}
        };
        assertThat(evaluator.evaluate(matrix, BET, math(10_000)).winLines())
                .noneMatch(w -> w.symbolId() == ACE);
    }

    @Test
    void capsTotalWinAndEmitsReasonCode() {
        int[][] matrix = {
                {ACE, ACE, ACE, ACE, ACE},
                {ACE, ACE, ACE, ACE, ACE},
                {ACE, ACE, ACE, ACE, ACE}
        };
        EvaluationResult result = evaluator.evaluate(matrix, BET, math(5));

        assertThat(result.reasonCodes()).contains("MAX_WIN_CAPPED");
        assertThat(result.totalWin()).isEqualByComparingTo("1215.00");   // 5 x bet
    }

    @Test
    void waysConfigRejectsPaylines() {
        assertThatThrownBy(() -> math(10_000, WinModel.WAYS,
                List.of(new Payline(1, new int[][]{{0, 0}, {0, 1}, {0, 2}, {0, 3}, {0, 4}})), payTable(), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not declare paylines");
    }

    @Test
    void waysConfigRejectsWildPayTableEntries() {
        PayTable withPayingWild = new PayTable(Map.of(
                ACE, Map.of(3, new BigDecimal("5")),
                WILD, Map.of(3, new BigDecimal("25"))
        ));
        assertThatThrownBy(() -> math(10_000, WinModel.WAYS, List.of(), withPayingWild, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wilds substitute only");
    }

    @Test
    void waysConfigRejectsWildOnReelZero() {
        // A wild on the leftmost reel could anchor a run of pure wilds - the one case where substitution
        // overlaps itself. Config forbids it outright rather than relying on the evaluator to cope.
        assertThatThrownBy(() -> math(10_000, WinModel.WAYS, List.of(), payTable(), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not place the WILD symbol");
    }

    @Test
    void paylineConfigStillAllowsWildOnReelZeroAndItsOwnPayTable() {
        // The constraints are WAYS-only. Payline games - which is all three shipped ones - keep both:
        // a line pays once, for the better of its wild run and its substituted run, so there is no overlap.
        PayTable withPayingWild = new PayTable(Map.of(
                ACE,  Map.of(3, new BigDecimal("5"), 4, new BigDecimal("20"), 5, new BigDecimal("100")),
                WILD, Map.of(3, new BigDecimal("25"))
        ));
        List<Payline> lines = List.of(new Payline(1, new int[][]{{1, 0}, {1, 1}, {1, 2}, {1, 3}, {1, 4}}));
        assertThat(math(10_000, WinModel.PAYLINES, lines, withPayingWild, false)).isNotNull();
    }

    private static PayTable payTable() {
        return new PayTable(Map.of(
                ACE,   Map.of(3, new BigDecimal("5"), 4, new BigDecimal("20"), 5, new BigDecimal("100")),
                KING,  Map.of(3, new BigDecimal("4"), 4, new BigDecimal("15"), 5, new BigDecimal("80")),
                QUEEN, Map.of(3, new BigDecimal("3"), 4, new BigDecimal("12"), 5, new BigDecimal("60"))
        ));
    }

    private SlotMathDefinition math(int maxWinMultiplier) {
        return math(maxWinMultiplier, WinModel.WAYS, List.of(), payTable(), true);
    }

    /**
     * @param wildFreeReelZero whether reel 0's strips omit WILD. Ways config requires it; pass {@code false}
     *                         to exercise that rule, or when building a PAYLINES fixture where it does not apply.
     */
    private SlotMathDefinition math(int maxWinMultiplier, WinModel winModel, List<Payline> paylines,
                                    PayTable payTable, boolean wildFreeReelZero) {
        List<Symbol> symbols = List.of(
                new Symbol(ACE, "ACE", SymbolType.STANDARD, null),
                new Symbol(KING, "KING", SymbolType.STANDARD, null),
                new Symbol(QUEEN, "QUEEN", SymbolType.STANDARD, null),
                new Symbol(FILLER, "FILLER", SymbolType.STANDARD, null),
                new Symbol(WILD, "WILD", SymbolType.WILD, SymbolType.STANDARD),
                new Symbol(SCATTER, "SCATTER", SymbolType.SCATTER, null)
        );
        // Ways runs are anchored on the leftmost reel, so config forbids a WILD there.
        ReelStrip filler = new ReelStrip(new int[]{ACE, KING, QUEEN, FILLER, WILD, SCATTER});
        ReelStrip reelZero = wildFreeReelZero
                ? new ReelStrip(new int[]{ACE, KING, QUEEN, FILLER, SCATTER})
                : filler;
        Map<ReelStripSet, List<ReelStrip>> strips = Map.of(
                ReelStripSet.BASE,       List.of(reelZero, filler, filler, filler, filler),
                ReelStripSet.POWER_BET,  List.of(reelZero, filler, filler, filler, filler),
                ReelStripSet.FREE_SPINS, List.of(reelZero, filler, filler, filler, filler)
        );
        return new SlotMathDefinition(
                "test-ways", "v1", new BigDecimal("96.0"),
                new Grid(3, 5),
                winModel,
                symbols,
                paylines,
                payTable,
                strips,
                new ScatterTriggers(3, 10, 5),
                new FreeSpinsConfig(true, false, 50),
                new PowerBetConfig(new BigDecimal("1.50")),
                List.of(),
                new PickCollectConfig(12,
                        new PickCollectCompletion(PickCollectCompletion.CompletionType.FIXED_PICKS, 5),
                        List.of(new PickTileWeight(PickTileType.BLANK, 10, null)),
                        5000, 0),
                new Limits(maxWinMultiplier),
                new BetConfig(List.of(new BigDecimal("0.20"), new BigDecimal("1.00")), new BigDecimal("1.00"))
        );
    }
}
