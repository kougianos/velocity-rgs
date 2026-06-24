package com.velocity.rgs.blackjack.engine;

import com.velocity.rgs.blackjack.config.BlackjackMathDefinition;
import com.velocity.rgs.blackjack.domain.BlackjackOutcome;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.velocity.rgs.blackjack.engine.BlackjackFixtures.bd;
import static com.velocity.rgs.blackjack.engine.BlackjackFixtures.cards;
import static org.assertj.core.api.Assertions.assertThat;

class BlackjackSettlementTest {

    private final BlackjackSettlement settlement = new BlackjackSettlement();
    private final BlackjackMathDefinition math = BlackjackFixtures.classic();

    private HandSettlement settle(String player, boolean natural, String stake, String dealer) {
        return settlement.settleHand(cards(player.split(",")), natural, bd(stake), cards(dealer.split(",")), math);
    }

    @Test
    void naturalBlackjackPaysThreeToTwo() {
        HandSettlement s = settle("AH,KD", true, "10.00", "10C,7D");
        assertThat(s.outcome()).isEqualTo(BlackjackOutcome.PLAYER_BLACKJACK);
        assertThat(s.payout()).isEqualByComparingTo("25.00"); // 10 + 10*1.5
    }

    @Test
    void regularWinPaysEvenMoney() {
        HandSettlement s = settle("10H,10D", false, "10.00", "10C,8D"); // 20 vs 18
        assertThat(s.outcome()).isEqualTo(BlackjackOutcome.WIN);
        assertThat(s.payout()).isEqualByComparingTo("20.00");
    }

    @Test
    void pushReturnsStake() {
        HandSettlement s = settle("10H,8D", false, "10.00", "10C,8H"); // 18 vs 18
        assertThat(s.outcome()).isEqualTo(BlackjackOutcome.PUSH);
        assertThat(s.payout()).isEqualByComparingTo("10.00");
    }

    @Test
    void playerBustLosesEvenIfDealerAlsoWouldBust() {
        HandSettlement s = settle("10H,10D,5C", false, "10.00", "10C,6H"); // player 25 bust
        assertThat(s.outcome()).isEqualTo(BlackjackOutcome.LOSE);
        assertThat(s.payout()).isEqualByComparingTo("0.00");
    }

    @Test
    void dealerBustIsAWin() {
        HandSettlement s = settle("10H,8D", false, "10.00", "10C,6H,KD"); // dealer 26
        assertThat(s.outcome()).isEqualTo(BlackjackOutcome.WIN);
    }

    @Test
    void doubledStakePaysOnTheDoubledAmount() {
        HandSettlement s = settle("5H,6D,9C", false, "20.00", "10C,8H"); // 20 vs 18, stake doubled to 20
        assertThat(s.outcome()).isEqualTo(BlackjackOutcome.WIN);
        assertThat(s.payout()).isEqualByComparingTo("40.00");
    }

    @Test
    void bothNaturalsPush() {
        HandSettlement s = settle("AH,KD", true, "10.00", "AC,KS");
        assertThat(s.outcome()).isEqualTo(BlackjackOutcome.PUSH);
        assertThat(s.payout()).isEqualByComparingTo("10.00");
    }

    @Test
    void dealerNaturalBeatsNonNaturalTwentyOne() {
        HandSettlement s = settle("10H,5D,6C", false, "10.00", "AC,KS"); // player 21 (3 cards) vs dealer BJ
        assertThat(s.outcome()).isEqualTo(BlackjackOutcome.LOSE);
    }

    @Test
    void insurancePaysTwoToOneOnDealerBlackjack() {
        assertThat(settlement.settleInsurance(bd("5.00"), true, math)).isEqualByComparingTo("15.00");
        assertThat(settlement.settleInsurance(bd("5.00"), false, math)).isEqualByComparingTo("0.00");
    }

    @Test
    void higherTotalWins() {
        assertThat(settle("10H,9D", false, "10.00", "10C,7H").outcome()).isEqualTo(BlackjackOutcome.WIN); // 19 v 17
        assertThat(settle("10H,7D", false, "10.00", "10C,9H").outcome()).isEqualTo(BlackjackOutcome.LOSE); // 17 v 19
    }
}
