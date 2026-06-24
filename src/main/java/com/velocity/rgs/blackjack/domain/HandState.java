package com.velocity.rgs.blackjack.domain;

import com.velocity.rgs.card.Card;
import com.velocity.rgs.card.HandValue;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Mutable working/persisted state of one player hand. Cards are held as compact {@link Card#code()} strings so
 * the whole hand round-trips through the {@code player_hands} JSONB column; the {@link #cardList()} /
 * {@link #handValue()} helpers expose the rich view the engine needs. {@code bet} is the total stake committed
 * to this hand (base, or {@code 2×} base once doubled). {@code outcome}/{@code payout} are filled in at
 * settlement.
 */
@Getter
@Setter
@NoArgsConstructor
public class HandState {

    private List<String> cards = new ArrayList<>();
    private BigDecimal bet;
    private HandStatus status = HandStatus.ACTIVE;
    private boolean doubled;
    private boolean fromSplit;
    private boolean splitAce;
    private BlackjackOutcome outcome;
    private BigDecimal payout;

    public static HandState of(BigDecimal bet, Card... initialCards) {
        HandState hand = new HandState();
        hand.bet = bet;
        for (Card c : initialCards) {
            hand.cards.add(c.code());
        }
        return hand;
    }

    public void addCard(Card card) {
        cards.add(card.code());
    }

    public List<Card> cardList() {
        return cards.stream().map(Card::fromCode).toList();
    }

    public HandValue handValue() {
        return HandValue.of(cardList());
    }
}
