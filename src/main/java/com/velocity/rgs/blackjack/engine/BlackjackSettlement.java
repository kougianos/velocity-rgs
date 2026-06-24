package com.velocity.rgs.blackjack.engine;

import com.velocity.rgs.blackjack.config.BlackjackMathDefinition;
import com.velocity.rgs.blackjack.domain.BlackjackOutcome;
import com.velocity.rgs.card.Card;
import com.velocity.rgs.card.HandValue;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Stateless blackjack settlement. Given a player hand and the dealer's final hand, decides the outcome and the
 * amount to credit back to the player (stake included, mirroring the roulette "credit the full return" money
 * model). All payout maths is server-side and exact; the deterministic engine tests assert every case
 * (blackjack 3:2, win 1:1, push, bust, double, split, insurance 2:1).
 */
@Component
public class BlackjackSettlement {

    /**
     * Settle one player hand against the dealer's final hand. {@code naturalBlackjack} marks the original
     * two-card 21 (paid at {@code blackjackPayout}); a drawn or post-split 21 is a normal win. {@code stake} is
     * the total committed to this hand (base, or 2× once doubled). Returned {@code payout} is what is credited
     * back to the player: blackjack {@code stake×(1+payout)}, win {@code stake×2}, push {@code stake}, loss 0.
     */
    public HandSettlement settleHand(List<Card> playerCards, boolean naturalBlackjack, BigDecimal stake,
                                     List<Card> dealerCards, BlackjackMathDefinition math) {
        HandValue player = HandValue.of(playerCards);
        HandValue dealer = HandValue.of(dealerCards);
        boolean dealerBlackjack = dealer.isBlackjack();

        BlackjackOutcome outcome;
        if (player.isBust()) {
            outcome = BlackjackOutcome.LOSE;
        } else if (naturalBlackjack) {
            outcome = dealerBlackjack ? BlackjackOutcome.PUSH : BlackjackOutcome.PLAYER_BLACKJACK;
        } else if (dealerBlackjack) {
            outcome = BlackjackOutcome.LOSE;
        } else if (dealer.isBust() || player.total() > dealer.total()) {
            outcome = BlackjackOutcome.WIN;
        } else if (player.total() < dealer.total()) {
            outcome = BlackjackOutcome.LOSE;
        } else {
            outcome = BlackjackOutcome.PUSH;
        }

        BigDecimal payout = switch (outcome) {
            case PLAYER_BLACKJACK -> stake.add(stake.multiply(math.blackjackPayout()));
            case WIN -> stake.multiply(BigDecimal.valueOf(2));
            case PUSH -> stake;
            case LOSE -> BigDecimal.ZERO;
        };
        return new HandSettlement(outcome, scale(payout));
    }

    /** Insurance pays {@code bet × (insurancePayout + 1)} iff the dealer has a natural blackjack, else 0. */
    public BigDecimal settleInsurance(BigDecimal insuranceBet, boolean dealerBlackjack,
                                      BlackjackMathDefinition math) {
        if (!dealerBlackjack) {
            return scale(BigDecimal.ZERO);
        }
        return scale(insuranceBet.add(insuranceBet.multiply(BigDecimal.valueOf(math.insurancePayout()))));
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
