package com.velocity.rgs.blackjack.engine;

import com.velocity.rgs.blackjack.domain.BlackjackOutcome;

import java.math.BigDecimal;

/**
 * The settled result of one player hand: its {@link BlackjackOutcome} and the {@code payout} credited back to
 * the player (stake included - see {@link BlackjackSettlement#settleHand}).
 */
public record HandSettlement(BlackjackOutcome outcome, BigDecimal payout) {
}
