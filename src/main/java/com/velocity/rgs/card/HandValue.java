package com.velocity.rgs.card;

import java.util.List;
import java.util.Objects;

/**
 * The blackjack-style value of a hand: the best total achievable by counting aces as 11 where possible and 1
 * where an 11 would bust. An immutable snapshot computed by {@link #of(List)}. Part of the reusable
 * CardEngine - any game that totals card pip values with soft aces can use it.
 */
public final class HandValue {

    private final int total;
    private final boolean soft;
    private final int cardCount;

    private HandValue(int total, boolean soft, int cardCount) {
        this.total = total;
        this.soft = soft;
        this.cardCount = cardCount;
    }

    public static HandValue of(List<Card> cards) {
        Objects.requireNonNull(cards, "cards");
        int total = 0;
        int softAces = 0; // aces currently counted as 11
        for (Card card : cards) {
            total += card.value();
            if (card.rank().isAce()) {
                softAces++;
            }
        }
        // Demote aces from 11 to 1 one at a time until the hand no longer busts (or no soft aces remain).
        while (total > 21 && softAces > 0) {
            total -= 10;
            softAces--;
        }
        return new HandValue(total, softAces > 0, cards.size());
    }

    public int total() {
        return total;
    }

    /** True when an ace is still counted as 11 (a "soft" total such as A-6 = soft 17). */
    public boolean isSoft() {
        return soft;
    }

    public boolean isBust() {
        return total > 21;
    }

    /** A natural blackjack: exactly two cards totalling 21. */
    public boolean isBlackjack() {
        return cardCount == 2 && total == 21;
    }

    /**
     * True when the two cards form a splittable pair - exactly two cards of equal blackjack value, so a King
     * and a Queen (both 10) qualify, matching the common casino rule of splitting any two ten-value cards.
     */
    public static boolean isSplittablePair(List<Card> cards) {
        return cards.size() == 2 && cards.get(0).value() == cards.get(1).value();
    }
}
