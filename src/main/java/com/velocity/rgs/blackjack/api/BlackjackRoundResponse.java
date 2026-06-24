package com.velocity.rgs.blackjack.api;

import com.velocity.rgs.blackjack.domain.BlackjackAction;
import lombok.Builder;

import java.math.BigDecimal;
import java.util.List;

/**
 * The shared response body for {@code POST /api/v1/blackjack/deal} and {@code /action} — a full snapshot of
 * the round the client renders verbatim. While the round is in progress the {@link DealerView} carries only
 * the dealer's upcard with {@code hidden=true}; the hole card is <b>never</b> serialized until the round
 * settles. {@code availableActions} is the server's authoritative list of what the player may do next.
 */
@Builder
public record BlackjackRoundResponse(
        String sessionId,
        long sessionVersion,
        String roundId,
        String mathVersion,
        String status,
        List<HandView> playerHands,
        int activeHandIndex,
        DealerView dealer,
        List<BlackjackAction> availableActions,
        BigDecimal totalBet,
        BigDecimal totalWin,
        BigDecimal balance,
        InsuranceView insurance
) {

    /** One playing card as the client renders it — all display-ready, no client-side card logic. */
    @Builder
    public record CardView(String rank, String suit, String suitSymbol, String color, String code) {
    }

    /** One player hand: its cards, best total, soft flag, play status, stake, and (once settled) result. */
    @Builder
    public record HandView(
            List<CardView> cards,
            int value,
            boolean soft,
            String status,
            BigDecimal bet,
            String outcome,
            BigDecimal payout
    ) {
    }

    /** The dealer's hand — only the upcard while {@code hidden}, the full hand and total once revealed. */
    @Builder
    public record DealerView(List<CardView> cards, Integer value, boolean hidden) {
    }

    /** The insurance side bet, when one was placed. */
    @Builder
    public record InsuranceView(BigDecimal bet, boolean resolved, boolean won, BigDecimal payout) {
    }
}
