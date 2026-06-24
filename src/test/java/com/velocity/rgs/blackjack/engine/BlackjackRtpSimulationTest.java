package com.velocity.rgs.blackjack.engine;

import com.velocity.rgs.blackjack.config.BlackjackMathDefinition;
import com.velocity.rgs.card.Card;
import com.velocity.rgs.card.HandValue;
import com.velocity.rgs.card.Shoe;
import com.velocity.rgs.rng.RandomNumberGenerator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Blackjack RTP is player-decision-dependent, so unlike slots/roulette there is no exact guard. This tagged
 * (excluded-by-default; run with {@code -Prtp}) simulation plays a large number of hands with a simplified
 * basic strategy and only asserts the return lands in a WIDE band around the ~99.4% basic-strategy figure —
 * a sanity net that the engine's settlement and dealer-play are wired together sensibly, not a precise claim.
 * The simplified strategy omits splits and some soft doubles, so the realised return sits a little below the
 * textbook figure.
 */
class BlackjackRtpSimulationTest {

    private static final class FastRng implements RandomNumberGenerator {
        private final Random random;
        FastRng(long seed) { this.random = new Random(seed); }
        @Override public int nextIndex(int boundExclusive) { return random.nextInt(boundExclusive); }
    }

    private enum Move { HIT, STAND, DOUBLE }

    private final BlackjackDealer dealer = new BlackjackDealer();
    private final BlackjackMathDefinition math = BlackjackFixtures.classic();

    @Test
    @Tag("slow")
    void basicStrategyReturnsNearHouseFigure() {
        long hands = 2_000_000L;
        FastRng rng = new FastRng(20260624L);
        double base = 1.0;
        double staked = 0;
        double returned = 0;

        Shoe shoe = Shoe.shuffled(math.decks(), rng);
        for (long i = 0; i < hands; i++) {
            if (shoe.needsReshuffle(0.25)) {
                shoe = Shoe.shuffled(math.decks(), rng);
            }
            List<Card> player = new ArrayList<>(List.of(shoe.draw(), shoe.draw()));
            List<Card> dealerCards = new ArrayList<>(List.of(shoe.draw(), shoe.draw()));
            int up = dealerCards.get(0).value();

            boolean playerNatural = HandValue.of(player).isBlackjack();
            boolean dealerNatural = HandValue.of(dealerCards).isBlackjack();

            double stake = base;
            staked += stake;

            // Peek: a dealer natural ends the hand immediately.
            if (dealerNatural) {
                if (playerNatural) returned += stake; // push
                continue;
            }
            if (playerNatural) {
                returned += stake + base * 1.5; // 3:2
                continue;
            }

            // Player decisions — simplified basic strategy, no splits.
            boolean firstDecision = true;
            while (true) {
                HandValue v = HandValue.of(player);
                if (v.isBust()) break;
                Move move = decide(v, up, firstDecision);
                if (move == Move.STAND) break;
                if (move == Move.DOUBLE) {
                    staked += base;
                    stake += base;
                    player.add(shoe.draw());
                    break;
                }
                player.add(shoe.draw()); // HIT
                firstDecision = false;
            }

            HandValue pv = HandValue.of(player);
            if (pv.isBust()) {
                continue; // lose the (possibly doubled) stake
            }
            List<Card> dealerFinal = dealer.play(dealerCards, shoe, math.dealerHitsSoft17());
            int dealerTotal = HandValue.of(dealerFinal).total();
            int playerTotal = pv.total();
            if (dealerTotal > 21 || playerTotal > dealerTotal) {
                returned += stake * 2;
            } else if (playerTotal == dealerTotal) {
                returned += stake; // push
            }
            // else the stake is lost
        }

        double rtp = returned / staked;
        System.out.printf("Blackjack basic-strategy RTP over %,d hands: %.4f%n", hands, rtp);
        assertThat(rtp).isBetween(0.93, 1.01);
    }

    private Move decide(HandValue v, int dealerUp, boolean firstDecision) {
        int total = v.total();
        boolean soft = v.isSoft();
        if (firstDecision && !soft) {
            if (total == 11) return Move.DOUBLE;
            if (total == 10 && dealerUp <= 9) return Move.DOUBLE;
            if (total == 9 && dealerUp >= 3 && dealerUp <= 6) return Move.DOUBLE;
        }
        if (soft) {
            if (total >= 19) return Move.STAND;
            if (total == 18) return (dealerUp >= 2 && dealerUp <= 8) ? Move.STAND : Move.HIT;
            return Move.HIT;
        }
        if (total >= 17) return Move.STAND;
        if (total >= 13) return dealerUp <= 6 ? Move.STAND : Move.HIT;
        if (total == 12) return (dealerUp >= 4 && dealerUp <= 6) ? Move.STAND : Move.HIT;
        return Move.HIT;
    }
}
