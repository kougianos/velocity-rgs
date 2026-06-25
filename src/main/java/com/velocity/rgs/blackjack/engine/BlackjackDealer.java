package com.velocity.rgs.blackjack.engine;

import com.velocity.rgs.card.Card;
import com.velocity.rgs.card.HandValue;
import com.velocity.rgs.card.Shoe;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Plays the dealer's hand to completion - the only "decision-making" the house does, and it is fully
 * deterministic given the card sequence. The dealer hits while the total is below 17; on a <i>soft</i> 17 it
 * hits only under H17 rules, otherwise stands (S17). Pure given the cards: the unit tests drive it with a
 * fixed {@link Shoe#fromState} so the behaviour is exactly reproducible.
 */
@Component
public class BlackjackDealer {

    public List<Card> play(List<Card> dealerCards, Shoe shoe, boolean hitSoft17) {
        List<Card> hand = new ArrayList<>(dealerCards);
        while (true) {
            HandValue value = HandValue.of(hand);
            int total = value.total();
            boolean mustHit;
            if (total < 17) {
                mustHit = true;
            } else if (total == 17 && value.isSoft() && hitSoft17) {
                mustHit = true; // soft 17 under H17 rules
            } else {
                mustHit = false; // hard 17+, or soft 17 under S17
            }
            if (!mustHit) {
                return hand;
            }
            hand.add(shoe.draw());
        }
    }
}
