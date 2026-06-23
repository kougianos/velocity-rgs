package com.velocity.rgs.roulette.engine;

import java.math.BigDecimal;
import java.util.List;

/**
 * The full result of evaluating a spin's bets against the winning number: the total amount to credit
 * ({@code totalWin} = the sum of winning bets' {@code amount × (payout + 1)}), the per-bet breakdown, and any
 * reason codes for the audit trail.
 */
public record RouletteEvaluation(BigDecimal totalWin, List<RouletteBetResult> results, List<String> reasonCodes) {
}
