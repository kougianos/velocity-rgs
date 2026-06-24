package com.velocity.rgs.blackjack.engine;

import com.velocity.rgs.card.Card;
import com.velocity.rgs.card.HandValue;
import com.velocity.rgs.card.Shoe;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.velocity.rgs.blackjack.engine.BlackjackFixtures.cards;
import static com.velocity.rgs.blackjack.engine.BlackjackFixtures.shoe;
import static org.assertj.core.api.Assertions.assertThat;

class BlackjackDealerTest {

    private final BlackjackDealer dealer = new BlackjackDealer();

    @Test
    void standsOnHardSeventeenWithoutDrawing() {
        Shoe shoe = shoe("5H", "5C"); // would be drawn if the dealer hit
        List<Card> result = dealer.play(cards("10H", "7D"), shoe, false);
        assertThat(result).hasSize(2);
        assertThat(HandValue.of(result).total()).isEqualTo(17);
        assertThat(shoe.drawIndex()).isZero();
    }

    @Test
    void standsOnSoftSeventeenUnderS17() {
        Shoe shoe = shoe("5H");
        List<Card> result = dealer.play(cards("AH", "6D"), shoe, false); // soft 17
        assertThat(result).hasSize(2);
        assertThat(HandValue.of(result).total()).isEqualTo(17);
    }

    @Test
    void hitsSoftSeventeenUnderH17() {
        Shoe shoe = shoe("4H"); // A,6,4 -> soft 21
        List<Card> result = dealer.play(cards("AH", "6D"), shoe, true);
        assertThat(result).hasSize(3);
        assertThat(HandValue.of(result).total()).isEqualTo(21);
    }

    @Test
    void hitsUntilSeventeenThenStands() {
        Shoe shoe = shoe("4H", "QD"); // 12 -> 16 -> would draw Q? 12+4=16 (<17) -> +Q busts? no: draw next
        List<Card> result = dealer.play(cards("10H", "2D"), shoe, false); // 12, draw 4 -> 16, draw Q -> 26 bust
        assertThat(HandValue.of(result).isBust()).isTrue();
    }

    @Test
    void drawsOnceToReachHardSeventeen() {
        Shoe shoe = shoe("5H", "9C");
        List<Card> result = dealer.play(cards("10H", "2D"), shoe, false); // 12 -> 17, stand
        assertThat(result).hasSize(3);
        assertThat(HandValue.of(result).total()).isEqualTo(17);
        assertThat(shoe.drawIndex()).isEqualTo(1);
    }
}
