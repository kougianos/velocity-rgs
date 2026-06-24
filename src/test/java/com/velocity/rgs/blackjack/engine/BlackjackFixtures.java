package com.velocity.rgs.blackjack.engine;

import com.velocity.rgs.blackjack.config.BlackjackLimits;
import com.velocity.rgs.blackjack.config.BlackjackMathDefinition;
import com.velocity.rgs.card.Card;
import com.velocity.rgs.card.Shoe;
import com.velocity.rgs.catalog.BetConfig;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/** Shared builders for the deterministic blackjack engine tests. */
final class BlackjackFixtures {

    private BlackjackFixtures() {
    }

    static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    static List<Card> cards(String... codes) {
        return Arrays.stream(codes).map(Card::fromCode).toList();
    }

    /** A shoe whose draw order is exactly the supplied cards (drawn front to back). */
    static Shoe shoe(String... codes) {
        return Shoe.fromState(cards(codes), 0);
    }

    static BetConfig betConfig() {
        return new BetConfig(List.of(
                bd("1.00"), bd("2.00"), bd("5.00"), bd("10.00"), bd("25.00"),
                bd("50.00"), bd("100.00"), bd("250.00"), bd("500.00")), bd("5.00"));
    }

    /** Classic Vegas rules: 6-deck, S17, 3:2, DAS, split to 3, insurance 2:1. */
    static BlackjackMathDefinition classic() {
        return custom(false, true, 2);
    }

    static BlackjackMathDefinition custom(boolean dealerHitsSoft17, boolean doubleAfterSplit, int maxSplits) {
        return new BlackjackMathDefinition(
                "classic-blackjack", "v1", "CLASSIC", bd("99.40"),
                6, dealerHitsSoft17, bd("1.5"), doubleAfterSplit, maxSplits, true, 2,
                betConfig(), new BlackjackLimits(bd("500.00")));
    }
}
