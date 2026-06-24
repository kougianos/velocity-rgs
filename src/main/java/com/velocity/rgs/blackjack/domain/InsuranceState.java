package com.velocity.rgs.blackjack.domain;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Working/persisted state of the optional insurance side bet (offered when the dealer's upcard is an Ace).
 * {@code bet} is the staked amount (half the base bet); once the dealer peeks, {@code resolved} flips and the
 * bet either wins (dealer had blackjack → {@code payout = bet × (insurancePayout + 1)}) or loses
 * ({@code payout = 0}).
 */
@Getter
@Setter
@NoArgsConstructor
public class InsuranceState {

    private BigDecimal bet;
    private boolean resolved;
    private boolean won;
    private BigDecimal payout;

    public static InsuranceState of(BigDecimal bet) {
        InsuranceState state = new InsuranceState();
        state.bet = bet;
        return state;
    }
}
