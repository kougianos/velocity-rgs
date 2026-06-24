package com.velocity.rgs.card;

import java.util.Objects;

/**
 * An immutable playing card: a {@link Rank} and a {@link Suit}. Its {@link #code()} ("AS", "10H", "KD") is a
 * stable, compact string used to persist a shuffled shoe and rebuild it later via {@link #fromCode(String)},
 * so a multi-step round replays deterministically. Part of the reusable CardEngine.
 */
public record Card(Rank rank, Suit suit) {

    public Card {
        Objects.requireNonNull(rank, "rank");
        Objects.requireNonNull(suit, "suit");
    }

    /** Compact persistence/display code — rank code followed by the suit code, e.g. {@code "10H"}, {@code "AS"}. */
    public String code() {
        return rank.code() + suit.code();
    }

    /** The card's blackjack value (Ace = 11; reduced by {@link HandValue} when needed). */
    public int value() {
        return rank.value();
    }

    public CardColor color() {
        return suit.color();
    }

    /** Inverse of {@link #code()} — the last char is the suit, everything before it the rank. */
    public static Card fromCode(String code) {
        if (code == null || code.length() < 2) {
            throw new IllegalArgumentException("Invalid card code: " + code);
        }
        char suitCode = code.charAt(code.length() - 1);
        String rankCode = code.substring(0, code.length() - 1);
        return new Card(Rank.fromCode(rankCode), Suit.fromCode(suitCode));
    }

    @Override
    public String toString() {
        return code();
    }
}
