package com.velocity.rgs.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Kills issued JWTs before they expire, keyed on the token's {@code jti} (§4.2).
 *
 * <p>Self-exclusion has to reach a player who is <em>already playing</em>. Their token is signed, valid
 * and good for another hour, so nothing about verification will refuse it and waiting for expiry means
 * an account closed at 9:00 keeps playing until 10:00. A denylist is the difference between an account
 * that is closed and an account that will be closed shortly.
 *
 * <p>Two keys, both in Redis and nowhere else:
 * <ul>
 *   <li>{@code rgs:auth:jti:{playerId}} - a hash of every live {@code jti} minted for that player to the
 *       second it expires. This is the index, because severing needs to name tokens and a JWT is
 *       stateless precisely so the server does not have to remember it.</li>
 *   <li>{@code rgs:auth:denied:{jti}} - one key per severed token, holding the reason.</li>
 * </ul>
 *
 * <p>No Postgres mirror, deliberately. Every entry here is dead the moment the token it names would have
 * expired anyway, so each one carries a TTL that does exactly that; a durable copy would outlive its own
 * subject and turn a self-cleaning cache into a table someone has to prune.
 *
 * <p><b>On Redis being unavailable.</b> Reads fail open, in line with every other Redis use in this
 * codebase, and that is safe here for a specific reason rather than by assumption: the denylist is not
 * what stops a self-excluded player betting. {@code RgPolicyService.validateStake} reads
 * {@code rg_limit} inside the same transaction that moves the money and refuses the stake there. The
 * denylist makes the refusal immediate and total instead of arriving one API call later. Losing it costs
 * promptness, never the block itself.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenDenylist {

    /** Why a token was severed. The only reason today, and stored rather than assumed so a second one
     *  is a new constant instead of a new key layout. */
    public static final String SELF_EXCLUDED = "SELF_EXCLUDED";

    private static final String ISSUED_KEY_PREFIX = "rgs:auth:jti:";
    private static final String DENIED_KEY_PREFIX = "rgs:auth:denied:";

    private final StringRedisTemplate redisTemplate;

    /**
     * Records a freshly minted token against its player so it can be named later.
     *
     * <p>Called at mint time rather than on first use: a token that has been issued but not yet used is
     * exactly the one sitting in a tab somebody walked away from, which is the case this whole class
     * exists for.
     */
    public void register(String playerId, String jti, Instant expiresAt) {
        if (playerId == null || jti == null || expiresAt == null) {
            return;
        }
        Duration ttl = Duration.between(Instant.now(), expiresAt);
        if (ttl.isNegative() || ttl.isZero()) {
            return;
        }
        try {
            String key = issuedKey(playerId);
            hash().put(key, jti, Long.toString(expiresAt.getEpochSecond()));
            // The index has to outlive the longest-lived token in it, so the TTL only ever extends. A
            // shorter-lived token minted afterwards must not drag the expiry back over its siblings.
            Long currentTtl = redisTemplate.getExpire(key);
            if (currentTtl == null || currentTtl < ttl.getSeconds()) {
                redisTemplate.expire(key, ttl);
            }
        } catch (RuntimeException ex) {
            log.debug("Token index write failed for {}: {}", playerId, ex.getMessage());
        }
    }

    /**
     * Severs every live token the player holds, returning how many were still worth severing.
     *
     * <p>The index is left in place afterwards rather than cleared. It is what {@link #clear} walks to
     * undo this, and keeping it makes a second call re-sever anything minted since the first - which
     * matters because minting is anonymous in demo mode, so a severed player can always obtain a fresh
     * token. That token is not a way back in: it passes the filter and is then refused at the stake by
     * {@code RgPolicyService}, which reads the database rather than this cache.
     */
    public int severAllForPlayer(String playerId, String reason) {
        if (playerId == null) {
            return 0;
        }
        try {
            Map<String, String> issued = hash().entries(issuedKey(playerId));
            Instant now = Instant.now();
            int severed = 0;
            for (Map.Entry<String, String> entry : issued.entrySet()) {
                Duration remaining = remainingLife(entry.getValue(), now);
                if (remaining == null) {
                    continue;
                }
                redisTemplate.opsForValue().set(deniedKey(entry.getKey()), reason, remaining);
                severed++;
            }
            log.info("Severed {} live token(s) for player={} reason={}", severed, playerId, reason);
            return severed;
        } catch (RuntimeException ex) {
            log.warn("Token severing failed for {}: {}", playerId, ex.getMessage());
            return 0;
        }
    }

    /** The reason this token was severed, or null if it is still good. */
    public String denialReason(String jti) {
        if (jti == null) {
            return null;
        }
        try {
            return redisTemplate.opsForValue().get(deniedKey(jti));
        } catch (RuntimeException ex) {
            log.debug("Denylist read failed for jti={}: {}", jti, ex.getMessage());
            return null;
        }
    }

    /**
     * Un-severs every token in the player's index.
     *
     * <p>Paired with {@code RgPolicyService.resetForDemo} and reachable only from there, for the same
     * reason that method exists: the demo has to be replayable. Nothing in the player-facing API calls
     * this.
     */
    public void clear(String playerId) {
        if (playerId == null) {
            return;
        }
        try {
            String key = issuedKey(playerId);
            for (String jti : hash().keys(key)) {
                redisTemplate.delete(deniedKey(jti));
            }
            redisTemplate.delete(key);
        } catch (RuntimeException ex) {
            log.debug("Denylist clear failed for {}: {}", playerId, ex.getMessage());
        }
    }

    // ---------------------------------------------------------------- internals

    /** Null when the token has already expired on its own and needs no denial to stop it. */
    private static Duration remainingLife(String expiryEpochSeconds, Instant now) {
        try {
            Duration remaining =
                    Duration.between(now, Instant.ofEpochSecond(Long.parseLong(expiryEpochSeconds)));
            return remaining.isNegative() || remaining.isZero() ? null : remaining;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private HashOperations<String, String, String> hash() {
        return redisTemplate.opsForHash();
    }

    static String issuedKey(String playerId) {
        return ISSUED_KEY_PREFIX + playerId;
    }

    static String deniedKey(String jti) {
        return DENIED_KEY_PREFIX + jti;
    }
}
