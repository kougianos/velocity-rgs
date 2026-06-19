package com.velocity.rgs.math.engine;

import com.velocity.rgs.math.config.FreeSpinsConfig;
import com.velocity.rgs.math.config.Grid;
import com.velocity.rgs.math.config.Limits;
import com.velocity.rgs.math.config.PickCollectCompletion;
import com.velocity.rgs.math.config.PickCollectConfig;
import com.velocity.rgs.math.config.PickTileWeight;
import com.velocity.rgs.math.config.PowerBetConfig;
import com.velocity.rgs.math.config.ScatterTriggers;
import com.velocity.rgs.math.config.SlotMathDefinition;
import com.velocity.rgs.math.domain.Payline;
import com.velocity.rgs.math.domain.PayTable;
import com.velocity.rgs.math.domain.PickTileType;
import com.velocity.rgs.math.domain.ReelStrip;
import com.velocity.rgs.math.domain.ReelStripSet;
import com.velocity.rgs.math.domain.Symbol;
import com.velocity.rgs.math.domain.SymbolType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReelEvaluatorTest {

    private static final int ACE = 1;
    private static final int KING = 2;
    private static final int QUEEN = 3;
    private static final int WILD = 9;
    private static final int SCATTER = 12;

    private final ReelEvaluator evaluator = new ReelEvaluator();

    @Test
    void fiveOfAKindPaysOnSingleLine() {
        int[][] matrix = {
                {KING,  QUEEN, QUEEN, QUEEN, QUEEN},
                {ACE,   ACE,   ACE,   ACE,   ACE},
                {QUEEN, KING,  KING,  KING,  KING}
        };
        EvaluationResult result = evaluator.evaluate(matrix, new BigDecimal("3.00"), math(10_000));

        assertThat(result.totalWin()).isEqualByComparingTo("100.00");
        assertThat(result.winLines()).hasSize(1);
        WinLine win = result.winLines().get(0);
        assertThat(win.lineId()).isEqualTo(2);
        assertThat(win.symbolId()).isEqualTo(ACE);
        assertThat(win.count()).isEqualTo(5);
        assertThat(win.payout()).isEqualByComparingTo("100.00");
        assertThat(result.reasonCodes()).isEmpty();
    }

    @Test
    void wildSubstitutesForStandard() {
        // Line 2 (middle row): WILD ACE ACE ACE KING → 4 ACEs.
        int[][] matrix = {
                {KING,  KING,  KING,  KING,  KING},
                {WILD,  ACE,   ACE,   ACE,   KING},
                {QUEEN, QUEEN, QUEEN, QUEEN, QUEEN}
        };
        EvaluationResult result = evaluator.evaluate(matrix, new BigDecimal("3.00"), math(10_000));

        assertThat(result.winLines()).anyMatch(w -> w.lineId() == 2 && w.symbolId() == ACE && w.count() == 4);
    }

    @Test
    void scatterBreaksLineRun() {
        // Line 2: ACE ACE SCATTER ACE ACE → only 2 ACEs before scatter → no payout.
        int[][] matrix = {
                {KING,  KING,  KING,    KING,  KING},
                {ACE,   ACE,   SCATTER, ACE,   ACE},
                {QUEEN, QUEEN, QUEEN,   QUEEN, QUEEN}
        };
        EvaluationResult result = evaluator.evaluate(matrix, new BigDecimal("3.00"), math(10_000));

        assertThat(result.winLines()).noneMatch(w -> w.lineId() == 2);
    }

    @Test
    void partialLineThreeOfAKindPays() {
        // Line 2: ACE ACE ACE KING QUEEN → 3 ACEs.
        int[][] matrix = {
                {KING,  KING,  KING,  KING,  KING},
                {ACE,   ACE,   ACE,   KING,  QUEEN},
                {QUEEN, QUEEN, QUEEN, QUEEN, QUEEN}
        };
        EvaluationResult result = evaluator.evaluate(matrix, new BigDecimal("3.00"), math(10_000));

        WinLine middle = result.winLines().stream()
                .filter(w -> w.lineId() == 2).findFirst().orElseThrow();
        assertThat(middle.symbolId()).isEqualTo(ACE);
        assertThat(middle.count()).isEqualTo(3);
        assertThat(middle.payout()).isEqualByComparingTo("5.00");
    }

    @Test
    void leadingWildsPickHigherOfWildOrBaseRun() {
        // Line 2: WILD WILD WILD QUEEN QUEEN.
        // Wild-only 3-of-a-kind pays 25; base-run (QUEEN) is only 2 reels (wilds + queens stop at 5
        // → run = wild,wild,wild,queen,queen = 5 queens via wild substitution).
        // 5 queens pay 60 (> 25 wild-3) so base wins.
        int[][] matrix = {
                {KING,  KING,  KING,  KING,  KING},
                {WILD,  WILD,  WILD,  QUEEN, QUEEN},
                {ACE,   ACE,   ACE,   ACE,   ACE}
        };
        EvaluationResult result = evaluator.evaluate(matrix, new BigDecimal("3.00"), math(10_000));

        WinLine middle = result.winLines().stream()
                .filter(w -> w.lineId() == 2).findFirst().orElseThrow();
        assertThat(middle.symbolId()).isEqualTo(QUEEN);
        assertThat(middle.count()).isEqualTo(5);
        assertThat(middle.payout()).isEqualByComparingTo("60.00");
    }

    @Test
    void capsTotalWinAndEmitsReasonCode() {
        // Tight cap (cap multiplier = 5 × bet 1.00 = 5.00). Even a tiny 3-of-a-kind ACE (5.00) hits cap.
        int[][] matrix = {
                {KING,  KING,  KING,  KING,  KING},
                {ACE,   ACE,   ACE,   ACE,   ACE},
                {QUEEN, QUEEN, QUEEN, QUEEN, QUEEN}
        };
        EvaluationResult result = evaluator.evaluate(matrix, new BigDecimal("3.00"), math(1));

        assertThat(result.reasonCodes()).contains("MAX_WIN_CAPPED");
        assertThat(result.totalWin()).isEqualByComparingTo("3.00");
    }

    private SlotMathDefinition math(int maxWinMultiplier) {
        List<Symbol> symbols = List.of(
                new Symbol(ACE, "ACE", SymbolType.STANDARD, null),
                new Symbol(KING, "KING", SymbolType.STANDARD, null),
                new Symbol(QUEEN, "QUEEN", SymbolType.STANDARD, null),
                new Symbol(WILD, "WILD", SymbolType.WILD, SymbolType.STANDARD),
                new Symbol(SCATTER, "SCATTER", SymbolType.SCATTER, null)
        );
        List<Payline> paylines = List.of(
                new Payline(1, new int[][]{{0,0},{0,1},{0,2},{0,3},{0,4}}),
                new Payline(2, new int[][]{{1,0},{1,1},{1,2},{1,3},{1,4}}),
                new Payline(3, new int[][]{{2,0},{2,1},{2,2},{2,3},{2,4}})
        );
        PayTable payTable = new PayTable(Map.of(
                ACE,   Map.of(3, new BigDecimal("5"),  4, new BigDecimal("20"), 5, new BigDecimal("100")),
                KING,  Map.of(3, new BigDecimal("4"),  4, new BigDecimal("15"), 5, new BigDecimal("80")),
                QUEEN, Map.of(3, new BigDecimal("3"),  4, new BigDecimal("12"), 5, new BigDecimal("60")),
                WILD,  Map.of(3, new BigDecimal("25"), 4, new BigDecimal("100"), 5, new BigDecimal("500"))
        ));
        ReelStrip filler = new ReelStrip(new int[]{ACE, KING, QUEEN, WILD, SCATTER, ACE, KING, QUEEN, WILD, SCATTER});
        Map<ReelStripSet, List<ReelStrip>> strips = Map.of(
                ReelStripSet.BASE,       List.of(filler, filler, filler, filler, filler),
                ReelStripSet.POWER_BET,  List.of(filler, filler, filler, filler, filler),
                ReelStripSet.FREE_SPINS, List.of(filler, filler, filler, filler, filler)
        );
        return new SlotMathDefinition(
                "test", "v1", new BigDecimal("96.0"),
                new Grid(3, 5),
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
                        5000),
                new Limits(maxWinMultiplier)
        );
    }
}
