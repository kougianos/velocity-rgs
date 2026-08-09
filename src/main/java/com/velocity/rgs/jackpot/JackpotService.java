package com.velocity.rgs.jackpot;

import com.velocity.rgs.common.money.Money;
import com.velocity.rgs.jackpot.domain.JackpotPool;
import com.velocity.rgs.jackpot.domain.JackpotPoolView;
import com.velocity.rgs.jackpot.domain.JackpotTier;
import com.velocity.rgs.jackpot.persistence.JackpotPoolRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

/**
 * Reads the progressive pools (§2).
 *
 * <p>Postgres is the source, on every read, with no cache in front of it yet. The lobby ticker gets a
 * Redis read cache later and this is the seam it goes behind - but it goes in front of a value that is
 * already correct, rather than becoming the place the value lives. "Why not just keep the pool in
 * Redis" has one answer: the pool is money owed to a player who has not won it yet, and an eviction
 * would be that money disappearing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JackpotService {

    private final JackpotPoolRepository poolRepository;

    /**
     * Every tier in one currency, smallest first.
     *
     * <p>Sorted by the enum's own order rather than by amount. A jackpot ladder is a fixed ladder, and
     * ordering by value would let the strip reorder itself the moment a lower tier was contributed past
     * a higher one that had just been won - which is a display that shuffles under a player who is
     * looking at it.
     */
    @Transactional(readOnly = true)
    public List<JackpotPoolView> pools(String currency) {
        return poolRepository.findByIdCurrency(currency).stream()
                .sorted(Comparator.comparing(JackpotPool::tier))
                .map(pool -> toView(pool, currency))
                .toList();
    }

    private static JackpotPoolView toView(JackpotPool pool, String currency) {
        JackpotTier tier = pool.tier();
        return new JackpotPoolView(
                tier,
                tier.label(),
                currency,
                scaled(pool.getAmount(), currency),
                scaled(pool.getSeedAmount(), currency),
                scaled(pool.grownBy(), currency));
    }

    /**
     * Money leaves at its own currency's scale. The column is NUMERIC(19,4), so a 10.00 pool reads back
     * as 10.0000 and would reach the client with two decimals nobody asked for.
     */
    private static BigDecimal scaled(BigDecimal amount, String currency) {
        return amount.setScale(Money.minorUnitScale(currency), RoundingMode.HALF_UP);
    }
}
