package com.velocity.rgs.jackpot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocity.rgs.jackpot.domain.JackpotPoolView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * A Redis read cache in front of the pools, for the lobby ticker only (§2).
 *
 * <p><b>This is the answer to "why not just keep the pool in Redis".</b> The ticker is a public page
 * that every visitor polls; serving it from Postgres means the busiest read in the product hits the
 * same four rows every spin is already contending on. So it is cached - and the cache holds a
 * <em>rendered view</em> of the pools, never the pools themselves.
 *
 * <p>The distinction is the whole design. A pool is money owed to a player who has not won it yet.
 * Redis is allowed to evict, and money that can be evicted is not money. Postgres stays the record;
 * this holds a copy that is allowed to be a second stale, because a jackpot figure that is a second
 * behind is a display detail, and one that is gone is a liability.
 *
 * <p>Every failure mode falls back to Postgres rather than surfacing: a cache that breaks makes the
 * page slower, never wrong.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JackpotPoolCache {

    /**
     * Short enough that a ticker never shows a figure anyone would call wrong, long enough to absorb
     * a lobby full of pollers. The pools move on every spin, so a longer TTL would not be "slightly
     * stale" - it would visibly stop counting.
     */
    static final Duration TTL = Duration.ofSeconds(2);

    private static final String KEY_PREFIX = "rgs:jackpot:pools:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public List<JackpotPoolView> get(String currency) {
        try {
            String raw = redisTemplate.opsForValue().get(key(currency));
            if (raw == null) {
                return null;
            }
            return objectMapper.readValue(raw, new TypeReference<List<JackpotPoolView>>() {});
        } catch (Exception ex) {
            log.debug("Jackpot pool cache read failed for {}: {}", currency, ex.getMessage());
            return null;
        }
    }

    public void put(String currency, List<JackpotPoolView> pools) {
        try {
            redisTemplate.opsForValue().set(key(currency), objectMapper.writeValueAsString(pools), TTL);
        } catch (Exception ex) {
            log.debug("Jackpot pool cache write failed for {}: {}", currency, ex.getMessage());
        }
    }

    /**
     * Drops the cached view.
     *
     * <p>Called when a pool is <em>won</em>, not when one is contributed to. A contribution moves a
     * figure by a fraction of a cent and can wait out the TTL; an award drops the pool by its entire
     * value, and showing the old number for even a second after that would be showing a prize that has
     * already been paid to somebody else.
     */
    public void evict(String currency) {
        try {
            redisTemplate.delete(key(currency));
        } catch (RuntimeException ex) {
            log.debug("Jackpot pool cache evict failed for {}: {}", currency, ex.getMessage());
        }
    }

    private static String key(String currency) {
        return KEY_PREFIX + currency;
    }
}
