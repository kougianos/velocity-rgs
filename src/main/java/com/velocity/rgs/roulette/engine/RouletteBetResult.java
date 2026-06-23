package com.velocity.rgs.roulette.engine;

import com.velocity.rgs.roulette.domain.RouletteBetKind;

import java.math.BigDecimal;

/**
 * The outcome of one placed {@link RouletteBet} after the wheel settles: whether it {@code won}, the "to-one"
 * {@code payout} that applied, and the {@code winAmount} credited for it ({@code amount × (payout + 1)} on a
 * win, else zero). Returned per bet so the client can highlight winning spots and the round can be audited.
 */
public record RouletteBetResult(
        RouletteBetKind kind,
        Integer number,
        BigDecimal amount,
        boolean won,
        int payout,
        BigDecimal winAmount
) {
}
