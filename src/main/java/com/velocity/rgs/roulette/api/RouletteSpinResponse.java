package com.velocity.rgs.roulette.api;

import com.velocity.rgs.session.domain.GameCommand;
import lombok.Builder;

import java.math.BigDecimal;
import java.util.List;

/** Response body for {@code POST /api/v1/roulette/spin}. */
@Builder
public record RouletteSpinResponse(
        String sessionId,
        long sessionVersion,
        String roundId,
        String mathVersion,
        int winningNumber,
        String winningColor,
        BigDecimal totalBet,
        BigDecimal totalWin,
        BigDecimal balance,
        List<WinningBetView> winningBets,
        List<GameCommand> availableActions
) {

    /** Per-bet settlement the client uses to highlight winning spots and show payouts. */
    @Builder
    public record WinningBetView(
            String type,
            Integer number,
            BigDecimal amount,
            boolean won,
            int payout,
            BigDecimal winAmount
    ) {
    }
}
