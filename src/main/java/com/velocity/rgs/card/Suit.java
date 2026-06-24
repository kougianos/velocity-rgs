package com.velocity.rgs.card;

/**
 * One of the four French-deck suits, carrying its short {@code code} (used in the compact card code that gets
 * persisted), a display {@code symbol} for the UI, and its {@link CardColor}. Part of the reusable, game-
 * agnostic CardEngine — no blackjack knowledge lives here.
 */
public enum Suit {
    CLUBS('C', "♣", CardColor.BLACK),
    DIAMONDS('D', "♦", CardColor.RED),
    HEARTS('H', "♥", CardColor.RED),
    SPADES('S', "♠", CardColor.BLACK);

    private final char code;
    private final String symbol;
    private final CardColor color;

    Suit(char code, String symbol, CardColor color) {
        this.code = code;
        this.symbol = symbol;
        this.color = color;
    }

    public char code() {
        return code;
    }

    public String symbol() {
        return symbol;
    }

    public CardColor color() {
        return color;
    }

    public static Suit fromCode(char code) {
        for (Suit suit : values()) {
            if (suit.code == code) {
                return suit;
            }
        }
        throw new IllegalArgumentException("Unknown suit code: " + code);
    }
}
