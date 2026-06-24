package com.velocity.rgs.blackjack.domain;

import com.velocity.rgs.card.Card;
import com.velocity.rgs.card.Shoe;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * Persisted snapshot of the round's {@link Shoe}: the full shuffled card order plus the current draw cursor.
 * Stored in the {@code shoe} JSONB column so multi-step play is deterministic and auditable — the order is
 * fixed at deal time (from the RNG, captured in {@code rng_draws}) and only {@link #drawIndex} advances as the
 * round progresses.
 */
@Getter
@Setter
@NoArgsConstructor
public class ShoeState {

    private List<String> cards;
    private int drawIndex;

    public static ShoeState of(Shoe shoe) {
        ShoeState state = new ShoeState();
        state.cards = shoe.cards().stream().map(Card::code).toList();
        state.drawIndex = shoe.drawIndex();
        return state;
    }

    public Shoe toShoe() {
        return Shoe.fromState(cards.stream().map(Card::fromCode).toList(), drawIndex);
    }
}
