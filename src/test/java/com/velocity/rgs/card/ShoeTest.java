package com.velocity.rgs.card;

import com.velocity.rgs.rng.RandomNumberGenerator;
import com.velocity.rgs.rng.RngDrawSink;
import com.velocity.rgs.rng.SecureRandomNumberGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShoeTest {

    /** A deterministic RNG so a shuffle is a pure function of the draws — handy for reproducible tests. */
    private static final class FixedRng implements RandomNumberGenerator {
        private final int value;
        FixedRng(int value) { this.value = value; }
        @Override public int nextIndex(int boundExclusive) { return value % boundExclusive; }
    }

    @Test
    void sixDeckShoeHasFullCardCount() {
        Shoe shoe = Shoe.shuffled(6, new FixedRng(0));
        assertThat(shoe.size()).isEqualTo(6 * 52);
        assertThat(shoe.remaining()).isEqualTo(312);
        assertThat(shoe.drawIndex()).isZero();
    }

    @Test
    void shuffleConsumesOneRngDrawPerSwap() {
        RngDrawSink sink = RngDrawSink.inMemory();
        Shoe.shuffled(1, new SecureRandomNumberGenerator(sink));
        // Fisher–Yates does n-1 swaps for n cards.
        assertThat(sink.drawn()).hasSize(52 - 1);
    }

    @Test
    void shuffleIsDeterministicGivenTheSameRng() {
        Shoe a = Shoe.shuffled(2, new FixedRng(3));
        Shoe b = Shoe.shuffled(2, new FixedRng(3));
        assertThat(a.cards()).isEqualTo(b.cards());
    }

    @Test
    void fromStateRestoresOrderAndCursor() {
        List<Card> order = List.of("AS", "10H", "5C", "KD").stream().map(Card::fromCode).toList();
        Shoe shoe = Shoe.fromState(order, 1);
        assertThat(shoe.remaining()).isEqualTo(3);
        assertThat(shoe.draw()).isEqualTo(Card.fromCode("10H"));
        assertThat(shoe.draw()).isEqualTo(Card.fromCode("5C"));
        assertThat(shoe.drawIndex()).isEqualTo(3);
    }

    @Test
    void drawAdvancesUntilExhausted() {
        Shoe shoe = Shoe.fromState(List.of(Card.fromCode("AS"), Card.fromCode("KD")), 0);
        shoe.draw();
        shoe.draw();
        assertThat(shoe.remaining()).isZero();
        assertThatThrownBy(shoe::draw).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsBadDeckCount() {
        assertThatThrownBy(() -> Shoe.shuffled(0, new FixedRng(0)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
