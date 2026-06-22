package com.velocity.rgs.math.engine;

import com.velocity.rgs.math.config.BetConfig;
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
import com.velocity.rgs.rng.RandomNumberGenerator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GridGenerationEngineTest {

    private static final int ACE = 1;
    private static final int KING = 2;
    private static final int QUEEN = 3;
    private static final int JACK = 4;
    private static final int TEN = 5;
    private static final int NINE = 6;
    private static final int EIGHT = 7;
    private static final int SEVEN = 8;
    private static final int WILD = 9;
    private static final int SCATTER = 12;

    private final GridGenerationEngine engine = new GridGenerationEngine();

    @Test
    void zeroStopsProduceFirstThreeRowsOfEveryReelStrip() {
        SlotMathDefinition math = math();
        RandomNumberGenerator rng = stub(0, 0, 0, 0, 0);

        GridGenerationResult result = engine.generate(math, ReelStripSet.BASE, rng);

        // Each strip starts with its first 6 symbols defined below; take first 3 as the visible window.
        List<ReelStrip> strips = math.reelStrips().get(ReelStripSet.BASE);
        assertThat(result.stopPositions()).containsExactly(0, 0, 0, 0, 0);
        for (int c = 0; c < 5; c++) {
            int[] s = strips.get(c).symbols();
            assertThat(result.matrix()[0][c]).isEqualTo(s[0]);
            assertThat(result.matrix()[1][c]).isEqualTo(s[1]);
            assertThat(result.matrix()[2][c]).isEqualTo(s[2]);
        }
    }

    @Test
    void stopNearEndOfStripWrapsAround() {
        SlotMathDefinition math = math();
        List<ReelStrip> strips = math.reelStrips().get(ReelStripSet.BASE);
        // Force each reel to stop at the last index of its strip → matrix should read [last, first, second].
        int last0 = strips.get(0).length() - 1;
        int last1 = strips.get(1).length() - 1;
        int last2 = strips.get(2).length() - 1;
        int last3 = strips.get(3).length() - 1;
        int last4 = strips.get(4).length() - 1;
        RandomNumberGenerator rng = stub(last0, last1, last2, last3, last4);

        GridGenerationResult result = engine.generate(math, ReelStripSet.BASE, rng);

        assertThat(result.stopPositions()).containsExactly(last0, last1, last2, last3, last4);
        for (int c = 0; c < 5; c++) {
            int[] s = strips.get(c).symbols();
            int n = s.length;
            assertThat(result.matrix()[0][c]).isEqualTo(s[n - 1]);
            assertThat(result.matrix()[1][c]).isEqualTo(s[0]);
            assertThat(result.matrix()[2][c]).isEqualTo(s[1]);
        }
    }

    @Test
    void selectsTheRequestedReelStripSet() {
        SlotMathDefinition math = math();
        RandomNumberGenerator rng = stub(0, 0, 0, 0, 0);

        GridGenerationResult base = engine.generate(math, ReelStripSet.BASE, stub(0, 0, 0, 0, 0));
        GridGenerationResult power = engine.generate(math, ReelStripSet.POWER_BET, rng);

        // POWER_BET strips in this fixture all start with SCATTER so the top row is all scatters.
        for (int c = 0; c < 5; c++) {
            assertThat(power.matrix()[0][c]).isEqualTo(SCATTER);
        }
        // Sanity: BASE top row must differ.
        assertThat(base.matrix()[0]).isNotEqualTo(power.matrix()[0]);
    }

    @Test
    void requestsOneDrawPerReelAtStripLength() {
        SlotMathDefinition math = math();
        RecordingRng rec = new RecordingRng();

        engine.generate(math, ReelStripSet.BASE, rec);

        List<Integer> bounds = rec.bounds;
        assertThat(bounds).hasSize(5);
        List<ReelStrip> strips = math.reelStrips().get(ReelStripSet.BASE);
        for (int c = 0; c < 5; c++) {
            assertThat(bounds.get(c)).isEqualTo(strips.get(c).length());
        }
    }

    /** Pops the next stop value off a fixed queue; ignores the requested bound. */
    private static RandomNumberGenerator stub(int... stops) {
        Deque<Integer> q = new ArrayDeque<>();
        for (int s : stops) q.add(s);
        return bound -> q.removeFirst();
    }

    private static final class RecordingRng implements RandomNumberGenerator {
        final java.util.List<Integer> bounds = new java.util.ArrayList<>();

        @Override
        public int nextIndex(int boundExclusive) {
            bounds.add(boundExclusive);
            return 0;
        }
    }

    private SlotMathDefinition math() {
        // Symbol ids include 8 STANDARD so test fixture strips can use them freely.
        List<Symbol> symbols = List.of(
                new Symbol(ACE, "ACE", SymbolType.STANDARD, null),
                new Symbol(KING, "KING", SymbolType.STANDARD, null),
                new Symbol(QUEEN, "QUEEN", SymbolType.STANDARD, null),
                new Symbol(JACK, "JACK", SymbolType.STANDARD, null),
                new Symbol(TEN, "TEN", SymbolType.STANDARD, null),
                new Symbol(NINE, "NINE", SymbolType.STANDARD, null),
                new Symbol(EIGHT, "EIGHT", SymbolType.STANDARD, null),
                new Symbol(SEVEN, "SEVEN", SymbolType.STANDARD, null),
                new Symbol(WILD, "WILD", SymbolType.WILD, SymbolType.STANDARD),
                new Symbol(SCATTER, "SCATTER", SymbolType.SCATTER, null)
        );
        List<Payline> paylines = List.of(
                new Payline(1, new int[][]{{0,0},{0,1},{0,2},{0,3},{0,4}})
        );
        PayTable payTable = new PayTable(Map.of(
                ACE, Map.of(3, new BigDecimal("5"), 4, new BigDecimal("20"), 5, new BigDecimal("100"))
        ));

        // BASE strips: distinct head symbols per reel for easy assertions; length 10 so wrap-around is meaningful.
        ReelStrip baseStrip0 = new ReelStrip(new int[]{ACE,   KING,  QUEEN, JACK, TEN,   NINE,  EIGHT, SEVEN, WILD, SCATTER});
        ReelStrip baseStrip1 = new ReelStrip(new int[]{KING,  QUEEN, JACK,  TEN,  NINE,  EIGHT, SEVEN, WILD,  SCATTER, ACE});
        ReelStrip baseStrip2 = new ReelStrip(new int[]{QUEEN, JACK,  TEN,   NINE, EIGHT, SEVEN, WILD,  SCATTER, ACE,   KING});
        ReelStrip baseStrip3 = new ReelStrip(new int[]{JACK,  TEN,   NINE,  EIGHT, SEVEN, WILD,  SCATTER, ACE,   KING,  QUEEN});
        ReelStrip baseStrip4 = new ReelStrip(new int[]{TEN,   NINE,  EIGHT, SEVEN, WILD,  SCATTER, ACE,   KING,  QUEEN, JACK});

        ReelStrip powerStrip = new ReelStrip(new int[]{SCATTER, ACE, KING, QUEEN, JACK, WILD, TEN, NINE, EIGHT, SEVEN});
        ReelStrip freeStrip  = new ReelStrip(new int[]{WILD, SCATTER, ACE, KING, QUEEN, JACK, TEN, NINE, EIGHT, SEVEN});

        Map<ReelStripSet, List<ReelStrip>> strips = Map.of(
                ReelStripSet.BASE,       List.of(baseStrip0, baseStrip1, baseStrip2, baseStrip3, baseStrip4),
                ReelStripSet.POWER_BET,  List.of(powerStrip, powerStrip, powerStrip, powerStrip, powerStrip),
                ReelStripSet.FREE_SPINS, List.of(freeStrip, freeStrip, freeStrip, freeStrip, freeStrip)
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
                        5000, 0),
                new Limits(10_000),
                new BetConfig(List.of(new BigDecimal("0.20"), new BigDecimal("1.00")), new BigDecimal("1.00"))
        );
    }
}
