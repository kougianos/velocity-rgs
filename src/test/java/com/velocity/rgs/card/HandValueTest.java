package com.velocity.rgs.card;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HandValueTest {

    private static List<Card> hand(String... codes) {
        return List.of(codes).stream().map(Card::fromCode).toList();
    }

    @Test
    void softTotalCountsAceAsEleven() {
        HandValue v = HandValue.of(hand("AH", "6C")); // soft 17
        assertThat(v.total()).isEqualTo(17);
        assertThat(v.isSoft()).isTrue();
        assertThat(v.isBust()).isFalse();
    }

    @Test
    void aceDemotesToOneToAvoidBust() {
        HandValue v = HandValue.of(hand("AH", "6C", "10D")); // 11+6+10 -> 7+... = 17 hard
        assertThat(v.total()).isEqualTo(17);
        assertThat(v.isSoft()).isFalse();
    }

    @Test
    void multipleAces() {
        HandValue v = HandValue.of(hand("AH", "AS")); // 11 + 1 = 12, still soft
        assertThat(v.total()).isEqualTo(12);
        assertThat(v.isSoft()).isTrue();
    }

    @Test
    void naturalBlackjackIsTwoCardTwentyOne() {
        assertThat(HandValue.of(hand("AH", "KD")).isBlackjack()).isTrue();
        assertThat(HandValue.of(hand("7H", "7D", "7C")).isBlackjack()).isFalse(); // 21 on 3 cards
        assertThat(HandValue.of(hand("7H", "7D", "7C")).total()).isEqualTo(21);
    }

    @Test
    void bustOverTwentyOne() {
        HandValue v = HandValue.of(hand("KH", "QD", "5C"));
        assertThat(v.total()).isEqualTo(25);
        assertThat(v.isBust()).isTrue();
    }

    @Test
    void splittablePairUsesValueEquality() {
        assertThat(HandValue.isSplittablePair(hand("8H", "8D"))).isTrue();
        assertThat(HandValue.isSplittablePair(hand("KH", "QD"))).isTrue(); // both worth 10
        assertThat(HandValue.isSplittablePair(hand("AH", "AS"))).isTrue();
        assertThat(HandValue.isSplittablePair(hand("KH", "9D"))).isFalse();
        assertThat(HandValue.isSplittablePair(hand("8H", "8D", "5C"))).isFalse();
    }
}
