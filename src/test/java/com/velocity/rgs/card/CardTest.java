package com.velocity.rgs.card;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CardTest {

    @Test
    void codeRoundTripsForEveryCard() {
        for (Suit suit : Suit.values()) {
            for (Rank rank : Rank.values()) {
                Card card = new Card(rank, suit);
                assertThat(Card.fromCode(card.code()))
                        .as("round-trip %s", card.code())
                        .isEqualTo(card);
            }
        }
    }

    @Test
    void tenValueCardCodesParseBack() {
        assertThat(Card.fromCode("10H")).isEqualTo(new Card(Rank.TEN, Suit.HEARTS));
        assertThat(Card.fromCode("AS")).isEqualTo(new Card(Rank.ACE, Suit.SPADES));
        assertThat(Card.fromCode("KD")).isEqualTo(new Card(Rank.KING, Suit.DIAMONDS));
    }

    @Test
    void colourFollowsTheSuit() {
        assertThat(new Card(Rank.ACE, Suit.HEARTS).color()).isEqualTo(CardColor.RED);
        assertThat(new Card(Rank.ACE, Suit.DIAMONDS).color()).isEqualTo(CardColor.RED);
        assertThat(new Card(Rank.ACE, Suit.SPADES).color()).isEqualTo(CardColor.BLACK);
        assertThat(new Card(Rank.ACE, Suit.CLUBS).color()).isEqualTo(CardColor.BLACK);
    }

    @Test
    void rankValuesAreBlackjackValues() {
        assertThat(Rank.ACE.value()).isEqualTo(11);
        assertThat(Rank.TEN.value()).isEqualTo(10);
        assertThat(Rank.KING.value()).isEqualTo(10);
        assertThat(Rank.SEVEN.value()).isEqualTo(7);
    }

    @Test
    void rejectsBadCodes() {
        assertThatThrownBy(() -> Card.fromCode("Z")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Card.fromCode("1X")).isInstanceOf(IllegalArgumentException.class);
    }
}
