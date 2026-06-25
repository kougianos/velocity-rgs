package com.velocity.rgs.blackjack.engine;

import com.velocity.rgs.blackjack.config.BlackjackMathDefinition;
import com.velocity.rgs.blackjack.domain.BlackjackAction;
import com.velocity.rgs.card.Card;
import com.velocity.rgs.card.HandValue;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Derives the structurally legal actions for the active hand from the rules and the hand's shape - the single
 * source of truth the client renders its buttons from (it is never trusted to decide for itself). INSURANCE is
 * offered only on the first decision while the dealer shows an Ace. DOUBLE needs a fresh two-card hand (and
 * DAS enabled if the hand came from a split); SPLIT needs a two-card matching pair below the hand cap.
 * Affordability against the wallet balance is layered on top by the service.
 */
@Component
public class BlackjackActions {

    public List<BlackjackAction> legalActions(List<Card> activeCards, boolean fromSplit, int handCount,
                                              boolean insuranceOffered, BlackjackMathDefinition math) {
        List<BlackjackAction> actions = new ArrayList<>();
        if (insuranceOffered) {
            actions.add(BlackjackAction.INSURANCE);
        }
        actions.add(BlackjackAction.HIT);
        actions.add(BlackjackAction.STAND);
        boolean twoCards = activeCards.size() == 2;
        if (twoCards && (!fromSplit || math.doubleAfterSplit())) {
            actions.add(BlackjackAction.DOUBLE);
        }
        if (twoCards && HandValue.isSplittablePair(activeCards) && handCount < math.maxHands()) {
            actions.add(BlackjackAction.SPLIT);
        }
        return actions;
    }
}
