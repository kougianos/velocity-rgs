package com.velocity.rgs.roulette.engine;

import com.velocity.rgs.roulette.config.RouletteBetTypeConfig;
import com.velocity.rgs.roulette.config.RouletteMathDefinition;
import com.velocity.rgs.roulette.domain.RouletteBetKind;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Stateless roulette settlement. Given the winning number and the placed bets, decides each bet against the
 * universal {@link RouletteGeometry} coverage and pays winners {@code amount × (payout + 1)} using the
 * configured "to-one" payout. All game logic lives here on the server - the client only renders the result.
 *
 * <p>Because standard payouts are exact, every bet's theoretical return is
 * {@code coverage / pocketCount × (payout + 1)} which equals {@code 36/37 = 97.30%} on a European wheel; the
 * verification test asserts this invariant via {@link #theoreticalRtp}.
 */
@Component
public class RouletteEvaluator {

    public RouletteEvaluation evaluate(int winningNumber, List<RouletteBet> bets, RouletteMathDefinition math) {
        if (bets == null || bets.isEmpty()) {
            throw new IllegalArgumentException("at least one bet is required");
        }
        if (winningNumber < 0 || winningNumber > math.highestNumber()) {
            throw new IllegalArgumentException(
                    "winningNumber " + winningNumber + " out of range [0, " + math.highestNumber() + "]");
        }

        List<RouletteBetResult> results = new ArrayList<>(bets.size());
        BigDecimal total = BigDecimal.ZERO;
        for (RouletteBet bet : bets) {
            RouletteBetTypeConfig cfg = math.betType(bet.kind()).orElseThrow(() ->
                    new IllegalArgumentException("bet type not offered by this game: " + bet.kind()));
            if (bet.amount().signum() <= 0) {
                throw new IllegalArgumentException("bet amount must be positive, found " + bet.amount());
            }
            Set<Integer> covered = RouletteGeometry.coveredNumbers(bet.kind(), bet.number(), math);
            boolean won = covered.contains(winningNumber);
            BigDecimal winAmount = won
                    ? bet.amount().multiply(BigDecimal.valueOf(cfg.payout() + 1L)).setScale(2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            results.add(new RouletteBetResult(bet.kind(), bet.number(), bet.amount(), won, cfg.payout(), winAmount));
            total = total.add(winAmount);
        }
        total = total.setScale(2, RoundingMode.HALF_UP);

        List<String> reasons = new ArrayList<>();
        if (total.signum() > 0) {
            reasons.add("ROULETTE_BETS_WON");
        } else {
            reasons.add("ROULETTE_NO_WIN");
        }
        return new RouletteEvaluation(total, results, reasons);
    }

    /**
     * The exact theoretical RTP of a single bet kind: {@code coverage/pocketCount × (payout + 1)}. Used by the
     * verification test to prove every configured bet returns the wheel's house-edge invariant (36/37 on a
     * European single-zero wheel).
     */
    public static BigDecimal theoreticalRtp(RouletteBetKind kind, RouletteMathDefinition math) {
        RouletteBetTypeConfig cfg = math.betType(kind).orElseThrow(() ->
                new IllegalArgumentException("bet type not offered by this game: " + kind));
        // Use a representative number for STRAIGHT (coverage size is 1 for any valid number).
        Integer number = kind.requiresNumber() ? 1 : null;
        int coverage = RouletteGeometry.coveredNumbers(kind, number, math).size();
        return BigDecimal.valueOf((long) coverage * (cfg.payout() + 1L))
                .divide(BigDecimal.valueOf(math.pocketCount()), 10, RoundingMode.HALF_UP);
    }
}
