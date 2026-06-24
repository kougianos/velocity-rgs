package com.velocity.rgs.card;

import com.velocity.rgs.rng.RandomNumberGenerator;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A multi-deck shoe of {@link Card}s with a sequential draw cursor. Game-agnostic — any card game deals from
 * it. Built once per round via {@link #shuffled(int, RandomNumberGenerator)} (Fisher–Yates over the round
 * RNG, so the shuffle is captured in the draw log and replays deterministically), or rebuilt from persisted
 * state via {@link #fromState(List, int)} so a multi-step round resumes exactly where it left off without
 * re-drawing the RNG. Mirrors how the slot grid / roulette wheel take their randomness from the round RNG.
 */
public final class Shoe {

    public static final int CARDS_PER_DECK = 52;

    private final List<Card> cards;
    private int drawIndex;

    private Shoe(List<Card> cards, int drawIndex) {
        this.cards = cards;
        this.drawIndex = drawIndex;
    }

    /**
     * A freshly shuffled {@code decks}-deck shoe. Fisher–Yates consumes exactly one {@code rng.nextIndex}
     * draw per swap, so the whole shuffle is recorded in the round's draw sink and can be replayed.
     */
    public static Shoe shuffled(int decks, RandomNumberGenerator rng) {
        if (decks < 1) {
            throw new IllegalArgumentException("decks must be >= 1, found " + decks);
        }
        Objects.requireNonNull(rng, "rng");
        List<Card> deck = new ArrayList<>(decks * CARDS_PER_DECK);
        for (int d = 0; d < decks; d++) {
            for (Suit suit : Suit.values()) {
                for (Rank rank : Rank.values()) {
                    deck.add(new Card(rank, suit));
                }
            }
        }
        // Fisher–Yates: walk from the top down, swapping each card with a uniformly chosen earlier-or-self one.
        for (int i = deck.size() - 1; i > 0; i--) {
            int j = rng.nextIndex(i + 1);
            Card tmp = deck.get(i);
            deck.set(i, deck.get(j));
            deck.set(j, tmp);
        }
        return new Shoe(deck, 0);
    }

    /** Rebuild a shoe from a persisted card order and draw cursor (no RNG — the order is already fixed). */
    public static Shoe fromState(List<Card> order, int drawIndex) {
        Objects.requireNonNull(order, "order");
        if (drawIndex < 0 || drawIndex > order.size()) {
            throw new IllegalArgumentException(
                    "drawIndex " + drawIndex + " out of range [0, " + order.size() + "]");
        }
        return new Shoe(new ArrayList<>(order), drawIndex);
    }

    /** Draw the next card and advance the cursor. */
    public Card draw() {
        if (drawIndex >= cards.size()) {
            throw new IllegalStateException("Shoe is exhausted");
        }
        return cards.get(drawIndex++);
    }

    public int remaining() {
        return cards.size() - drawIndex;
    }

    public int size() {
        return cards.size();
    }

    public int drawIndex() {
        return drawIndex;
    }

    /** The full card order (immutable copy) — persist this together with {@link #drawIndex()} to resume. */
    public List<Card> cards() {
        return List.copyOf(cards);
    }

    /** True when less than the {@code penetration} fraction of the shoe remains (reusable reshuffle trigger). */
    public boolean needsReshuffle(double penetration) {
        return remaining() < penetration * cards.size();
    }
}
