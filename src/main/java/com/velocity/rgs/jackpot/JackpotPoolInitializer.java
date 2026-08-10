package com.velocity.rgs.jackpot;

import com.velocity.rgs.jackpot.domain.JackpotPool;
import com.velocity.rgs.jackpot.domain.JackpotPoolId;
import com.velocity.rgs.jackpot.domain.JackpotTier;
import com.velocity.rgs.jackpot.persistence.JackpotPoolRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/**
 * Makes config the owner of the tier seeds (§2).
 *
 * <p>The migration inserts the four EUR pools so a fresh database is playable the moment it is up.
 * From then on the numbers live in {@code rgs.jackpot.tiers.*}, and this reconciles the rows to them at
 * every startup: it adds pools for a tier or currency that config has since introduced, and moves a
 * seed that config has since changed.
 *
 * <p><b>It never lowers a live pool.</b> Raising a seed above what a pool currently holds tops the pool
 * up to the new floor; lowering a seed changes the floor for next time and leaves the money where it
 * is. A pool is money owed to whoever wins it, so a config edit is allowed to promise more and is never
 * allowed to take back what is already promised.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JackpotPoolInitializer implements ApplicationRunner {

    private final JackpotProperties properties;
    private final JackpotPoolRepository poolRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        properties.validate();

        Instant now = Instant.now();
        for (String currency : properties.getCurrencies()) {
            for (JackpotTier tier : JackpotTier.values()) {
                BigDecimal seed = properties.tier(tier).getSeed();
                JackpotPoolId id = new JackpotPoolId(tier, currency);
                Optional<JackpotPool> existing = poolRepository.findById(id);

                if (existing.isEmpty()) {
                    poolRepository.save(JackpotPool.builder()
                            .id(id)
                            .amount(seed)
                            .seedAmount(seed)
                            .createdAt(now)
                            .updatedAt(now)
                            .build());
                    log.info("Seeded {} {} jackpot pool at {}", currency, tier, seed.toPlainString());
                    continue;
                }

                JackpotPool pool = existing.get();
                if (pool.getSeedAmount().compareTo(seed) == 0) {
                    continue;
                }
                log.info("Jackpot seed for {} {} moved {} -> {}", currency, tier,
                        pool.getSeedAmount().toPlainString(), seed.toPlainString());
                pool.setSeedAmount(seed);
                if (pool.getAmount().compareTo(seed) < 0) {
                    pool.setAmount(seed);
                }
                pool.setUpdatedAt(now);
                poolRepository.save(pool);
            }
        }
    }
}
