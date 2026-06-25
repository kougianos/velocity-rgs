package com.velocity.rgs.blackjack.domain;

import com.velocity.rgs.card.Card;
import com.velocity.rgs.card.HandValue;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Mutable working/persisted state of the dealer's hand. The <b>full</b> hand (including the hole card) is
 * always stored server-side in the {@code dealer_hand} JSONB column; the response DTO is responsible for
 * hiding the hole card while the round is in progress - it is never serialized to the client until settlement.
 */
@Getter
@Setter
@NoArgsConstructor
public class DealerState {

    private List<String> cards = new ArrayList<>();

    public static DealerState of(Card... initialCards) {
        DealerState dealer = new DealerState();
        for (Card c : initialCards) {
            dealer.cards.add(c.code());
        }
        return dealer;
    }

    public void addCard(Card card) {
        cards.add(card.code());
    }

    public List<Card> cardList() {
        return cards.stream().map(Card::fromCode).toList();
    }

    /** The dealer's face-up card (the first dealt). */
    public Card upcard() {
        return Card.fromCode(cards.get(0));
    }

    public HandValue handValue() {
        return HandValue.of(cardList());
    }
}
