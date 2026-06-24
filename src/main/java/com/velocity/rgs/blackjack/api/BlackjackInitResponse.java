package com.velocity.rgs.blackjack.api;

import com.velocity.rgs.blackjack.domain.BlackjackAction;
import lombok.Builder;

import java.math.BigDecimal;
import java.util.List;

/**
 * Response body for {@code POST /api/v1/blackjack/init}. Returns the stakes/limits/rules the client needs plus
 * the current balance and {@code availableActions}. If the session has an unsettled round (e.g. the player
 * reloaded mid-hand), {@code activeRound} carries its full state so the client can resume; otherwise it is
 * {@code null} and the only available action is {@code DEAL}.
 */
@Builder
public record BlackjackInitResponse(
        String sessionId,
        long sessionVersion,
        String gameId,
        String mathVersion,
        String currency,
        BigDecimal balance,
        List<BigDecimal> betValues,
        BigDecimal defaultBet,
        BigDecimal maxBet,
        RulesView rules,
        List<BlackjackAction> availableActions,
        BlackjackRoundResponse activeRound
) {

    /** The house rules, surfaced so the client can show them without hardcoding anything. */
    @Builder
    public record RulesView(
            int decks,
            boolean dealerHitsSoft17,
            BigDecimal blackjackPayout,
            boolean doubleAfterSplit,
            int maxSplits,
            boolean insuranceEnabled,
            int insurancePayout
    ) {
    }
}
