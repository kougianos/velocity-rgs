package com.velocity.rgs.roulette.engine;

import com.velocity.rgs.roulette.config.RouletteMathDefinition;
import com.velocity.rgs.roulette.domain.RouletteBetKind;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static com.velocity.rgs.roulette.domain.RouletteBetKind.BLACK;
import static com.velocity.rgs.roulette.domain.RouletteBetKind.COLUMN_1;
import static com.velocity.rgs.roulette.domain.RouletteBetKind.COLUMN_2;
import static com.velocity.rgs.roulette.domain.RouletteBetKind.COLUMN_3;
import static com.velocity.rgs.roulette.domain.RouletteBetKind.DOZEN_1;
import static com.velocity.rgs.roulette.domain.RouletteBetKind.DOZEN_2;
import static com.velocity.rgs.roulette.domain.RouletteBetKind.DOZEN_3;
import static com.velocity.rgs.roulette.domain.RouletteBetKind.EVEN;
import static com.velocity.rgs.roulette.domain.RouletteBetKind.HIGH;
import static com.velocity.rgs.roulette.domain.RouletteBetKind.LOW;
import static com.velocity.rgs.roulette.domain.RouletteBetKind.ODD;
import static com.velocity.rgs.roulette.domain.RouletteBetKind.RED;
import static com.velocity.rgs.roulette.domain.RouletteBetKind.STRAIGHT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Base-game behaviour and edge cases for the roulette settlement engine. */
class RouletteEvaluatorTest {

    private static final BigDecimal ONE = new BigDecimal("1.00");
    private final RouletteEvaluator evaluator = new RouletteEvaluator();
    private final RouletteMathDefinition math = RouletteFixtures.european();

    // ------------------------------------------------------------------ straight up

    @Test
    void straightHitPays36x() {
        RouletteEvaluation e = evaluator.evaluate(7, List.of(new RouletteBet(STRAIGHT, 7, ONE)), math);
        assertThat(e.totalWin()).isEqualByComparingTo("36.00");
        RouletteBetResult r = e.results().get(0);
        assertThat(r.won()).isTrue();
        assertThat(r.payout()).isEqualTo(35);
        assertThat(r.winAmount()).isEqualByComparingTo("36.00");
        assertThat(e.reasonCodes()).contains("ROULETTE_BETS_WON");
    }

    @Test
    void straightMissPaysNothing() {
        RouletteEvaluation e = evaluator.evaluate(8, List.of(new RouletteBet(STRAIGHT, 7, ONE)), math);
        assertThat(e.totalWin()).isEqualByComparingTo("0.00");
        assertThat(e.results().get(0).won()).isFalse();
        assertThat(e.reasonCodes()).contains("ROULETTE_NO_WIN");
    }

    @Test
    void straightOnZeroWinsButOutsideBetsAllLose() {
        List<RouletteBet> bets = List.of(
                new RouletteBet(STRAIGHT, 0, ONE),
                new RouletteBet(RED, null, ONE),
                new RouletteBet(EVEN, null, ONE),
                new RouletteBet(LOW, null, ONE),
                new RouletteBet(DOZEN_1, null, ONE),
                new RouletteBet(COLUMN_1, null, ONE));
        RouletteEvaluation e = evaluator.evaluate(0, bets, math);
        // Only the straight-up on 0 pays (36×); every outside bet loses on the green zero.
        assertThat(e.totalWin()).isEqualByComparingTo("36.00");
        assertThat(e.results()).filteredOn(RouletteBetResult::won).hasSize(1);
    }

    // ------------------------------------------------------------------ colours

    @Test
    void redWinsOnRedNumber() {
        // 1 is a red pocket.
        assertThat(evaluator.evaluate(1, List.of(new RouletteBet(RED, null, ONE)), math).totalWin())
                .isEqualByComparingTo("2.00");
    }

    @Test
    void redLosesOnBlackNumber() {
        // 2 is a black pocket.
        assertThat(evaluator.evaluate(2, List.of(new RouletteBet(RED, null, ONE)), math).totalWin())
                .isEqualByComparingTo("0.00");
        assertThat(evaluator.evaluate(2, List.of(new RouletteBet(BLACK, null, ONE)), math).totalWin())
                .isEqualByComparingTo("2.00");
    }

    // ------------------------------------------------------------------ even / odd

    @Test
    void evenAndOddExcludeZero() {
        assertThat(evaluator.evaluate(2, List.of(new RouletteBet(EVEN, null, ONE)), math).totalWin())
                .isEqualByComparingTo("2.00");
        assertThat(evaluator.evaluate(1, List.of(new RouletteBet(ODD, null, ONE)), math).totalWin())
                .isEqualByComparingTo("2.00");
        assertThat(evaluator.evaluate(0, List.of(new RouletteBet(EVEN, null, ONE)), math).totalWin())
                .isEqualByComparingTo("0.00");
        assertThat(evaluator.evaluate(0, List.of(new RouletteBet(ODD, null, ONE)), math).totalWin())
                .isEqualByComparingTo("0.00");
    }

    // ------------------------------------------------------------------ halves / dozens / columns boundaries

    @Test
    void lowHighBoundaries() {
        assertThat(won(18, LOW)).isTrue();
        assertThat(won(19, LOW)).isFalse();
        assertThat(won(19, HIGH)).isTrue();
        assertThat(won(18, HIGH)).isFalse();
        assertThat(won(0, LOW)).isFalse();
    }

    @Test
    void dozenBoundaries() {
        assertThat(won(12, DOZEN_1)).isTrue();
        assertThat(won(13, DOZEN_1)).isFalse();
        assertThat(won(13, DOZEN_2)).isTrue();
        assertThat(won(24, DOZEN_2)).isTrue();
        assertThat(won(25, DOZEN_2)).isFalse();
        assertThat(won(25, DOZEN_3)).isTrue();
        assertThat(won(36, DOZEN_3)).isTrue();
    }

    @Test
    void columnMembership() {
        assertThat(won(1, COLUMN_1)).isTrue();   // 1 % 3 == 1
        assertThat(won(34, COLUMN_1)).isTrue();
        assertThat(won(2, COLUMN_2)).isTrue();   // 2 % 3 == 2
        assertThat(won(35, COLUMN_2)).isTrue();
        assertThat(won(3, COLUMN_3)).isTrue();   // 3 % 3 == 0
        assertThat(won(36, COLUMN_3)).isTrue();
        assertThat(won(2, COLUMN_1)).isFalse();
    }

    @Test
    void dozensPay2to1() {
        assertThat(evaluator.evaluate(5, List.of(new RouletteBet(DOZEN_1, null, ONE)), math).totalWin())
                .isEqualByComparingTo("3.00");
    }

    // ------------------------------------------------------------------ combined

    @Test
    void multipleBetsAreSummed() {
        // Number 7 is red: straight-up on 7 (36×) plus a red bet (2×) = 38.
        List<RouletteBet> bets = List.of(
                new RouletteBet(STRAIGHT, 7, ONE),
                new RouletteBet(RED, null, ONE));
        RouletteEvaluation e = evaluator.evaluate(7, bets, math);
        assertThat(e.totalWin()).isEqualByComparingTo("38.00");
        assertThat(e.results()).filteredOn(RouletteBetResult::won).hasSize(2);
    }

    @Test
    void winAmountScalesWithStake() {
        RouletteEvaluation e = evaluator.evaluate(7,
                List.of(new RouletteBet(STRAIGHT, 7, new BigDecimal("5.00"))), math);
        assertThat(e.totalWin()).isEqualByComparingTo("180.00"); // 5 × 36
    }

    // ------------------------------------------------------------------ edge cases / guards

    @Test
    void emptyBetsRejected() {
        assertThatThrownBy(() -> evaluator.evaluate(7, List.of(), math))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void winningNumberOutOfRangeRejected() {
        assertThatThrownBy(() -> evaluator.evaluate(37, List.of(new RouletteBet(RED, null, ONE)), math))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void straightNumberOutOfRangeRejected() {
        assertThatThrownBy(() -> evaluator.evaluate(5, List.of(new RouletteBet(STRAIGHT, 99, ONE)), math))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void betTypeNotOfferedRejected() {
        RouletteMathDefinition redOnly = RouletteFixtures.redOnly();
        assertThatThrownBy(() ->
                evaluator.evaluate(7, List.of(new RouletteBet(STRAIGHT, 7, ONE)), redOnly))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private boolean won(int winningNumber, RouletteBetKind kind) {
        return evaluator.evaluate(winningNumber, List.of(new RouletteBet(kind, null, ONE)), math)
                .results().get(0).won();
    }
}
