package com.velocity.rgs.blackjack.engine;

import com.velocity.rgs.blackjack.config.BlackjackMathDefinition;
import com.velocity.rgs.blackjack.domain.BlackjackAction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.velocity.rgs.blackjack.engine.BlackjackFixtures.cards;
import static org.assertj.core.api.Assertions.assertThat;

class BlackjackActionsTest {

    private final BlackjackActions actions = new BlackjackActions();
    private final BlackjackMathDefinition classic = BlackjackFixtures.classic();

    @Test
    void freshHandOffersHitStandDouble() {
        List<BlackjackAction> a = actions.legalActions(cards("10H", "7D"), false, 1, false, classic);
        assertThat(a).contains(BlackjackAction.HIT, BlackjackAction.STAND, BlackjackAction.DOUBLE);
        assertThat(a).doesNotContain(BlackjackAction.SPLIT);
    }

    @Test
    void matchingPairOffersSplit() {
        List<BlackjackAction> a = actions.legalActions(cards("8H", "8D"), false, 1, false, classic);
        assertThat(a).contains(BlackjackAction.SPLIT, BlackjackAction.DOUBLE);
    }

    @Test
    void threeCardHandCannotDoubleOrSplit() {
        List<BlackjackAction> a = actions.legalActions(cards("5H", "3D", "2C"), false, 1, false, classic);
        assertThat(a).containsExactly(BlackjackAction.HIT, BlackjackAction.STAND);
    }

    @Test
    void doubleAfterSplitHonoursTheRule() {
        List<BlackjackAction> withDas = actions.legalActions(cards("5H", "6D"), true, 2, false, classic);
        assertThat(withDas).contains(BlackjackAction.DOUBLE);

        BlackjackMathDefinition noDas = BlackjackFixtures.custom(false, false, 2);
        List<BlackjackAction> withoutDas = actions.legalActions(cards("5H", "6D"), true, 2, false, noDas);
        assertThat(withoutDas).doesNotContain(BlackjackAction.DOUBLE);
    }

    @Test
    void cannotSplitBeyondTheHandCap() {
        // maxSplits=2 -> at most 3 hands; with 3 already out, no more splits.
        List<BlackjackAction> a = actions.legalActions(cards("8H", "8D"), true, 3, false, classic);
        assertThat(a).doesNotContain(BlackjackAction.SPLIT);
    }

    @Test
    void insuranceOnlyWhenOffered() {
        assertThat(actions.legalActions(cards("10H", "7D"), false, 1, true, classic))
                .contains(BlackjackAction.INSURANCE);
        assertThat(actions.legalActions(cards("10H", "7D"), false, 1, false, classic))
                .doesNotContain(BlackjackAction.INSURANCE);
    }
}
