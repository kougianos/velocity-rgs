package com.velocity.rgs.card;

/**
 * A card rank with its short {@code code} and its blackjack {@code value}. The Ace is valued 11 here (a
 * "soft" ace); {@link HandValue} reduces it to 1 when a hand would otherwise bust. Face cards are all worth
 * 10. Part of the reusable CardEngine; the values are blackjack-flavoured but any card game that totals pip
 * values can reuse them.
 */
public enum Rank {
    ACE("A", 11),
    TWO("2", 2),
    THREE("3", 3),
    FOUR("4", 4),
    FIVE("5", 5),
    SIX("6", 6),
    SEVEN("7", 7),
    EIGHT("8", 8),
    NINE("9", 9),
    TEN("10", 10),
    JACK("J", 10),
    QUEEN("Q", 10),
    KING("K", 10);

    private final String code;
    private final int value;

    Rank(String code, int value) {
        this.code = code;
        this.value = value;
    }

    public String code() {
        return code;
    }

    /** Blackjack value - Ace is 11 (soft); {@link HandValue} drops it to 1 to avoid a bust. */
    public int value() {
        return value;
    }

    public boolean isAce() {
        return this == ACE;
    }

    public static Rank fromCode(String code) {
        for (Rank rank : values()) {
            if (rank.code.equals(code)) {
                return rank;
            }
        }
        throw new IllegalArgumentException("Unknown rank code: " + code);
    }
}
