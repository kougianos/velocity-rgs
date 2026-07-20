package com.velocity.rgs.audit.replay;

import com.velocity.rgs.common.error.ErrorCode;
import com.velocity.rgs.common.error.RgsException;
import com.velocity.rgs.config.SecurityProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Mints and verifies the signed, expiring tokens behind public round-replay links (§3.1).
 *
 * <p>A link is a bearer capability handed to someone with no account, so the token is built to carry
 * exactly one authority and no more:
 *
 * <ol>
 *   <li><b>Scoped to one round.</b> The round id is the token's {@code sub}, and
 *       {@link PublicReplayController} takes <em>no</em> round parameter - it reads the id back out of
 *       the verified token. There is therefore no request field to tamper with: a link cannot be pointed
 *       at a round it was not minted for, and holding one grants nothing that would let its bearer walk
 *       the round table.</li>
 *   <li><b>Not a player credential.</b> The signing key is derived from the JWT secret through
 *       {@link #KEY_PURPOSE} rather than being the JWT secret, so a replay token fails signature
 *       verification in {@code JwtAuthenticationFilter} and a player's JWT fails verification here. The
 *       two token families cannot be swapped even though one secret seeds both.</li>
 *   <li><b>Anonymous by construction.</b> The identity claims a player token carries - subject player,
 *       session, currency, roles - are simply absent, and the payload the endpoint returns is redacted
 *       to match (see {@link PublicRoundReplay}).</li>
 * </ol>
 *
 * <p>Distinct issuer, and a {@code jti} on every token: the id is what a future denylist would key on if
 * per-link revocation is ever wanted, which today is deliberately not built (see
 * {@link PublicReplayProperties}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PublicReplayTokenService {

    /** Distinct from the player-token issuer, so the two are never confused even at a glance. */
    static final String ISSUER = "velocity-rgs-replay";

    /**
     * Domain-separation label for the derived signing key. Changing this string invalidates every
     * outstanding replay link without touching player authentication.
     */
    private static final String KEY_PURPOSE = "velocity-rgs/public-replay-link/v1";

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final SecurityProperties securityProperties;
    private final PublicReplayProperties replayProperties;

    private volatile SecretKey cachedKey;

    /** A minted link: the opaque token plus the moment it stops working. */
    public record SignedReplayLink(String token, Instant expiresAt, long ttlSeconds) {}

    /** A token that verified: the one round it authorises, and when it lapses. */
    public record VerifiedReplayLink(String roundId, Instant expiresAt) {}

    /**
     * Signs a link granting read access to exactly {@code roundId}, valid for
     * {@link PublicReplayProperties#getPublicLinkTtl()}.
     */
    public SignedReplayLink mint(String roundId) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(replayProperties.getPublicLinkTtl());
        String token = Jwts.builder()
                .issuer(ISSUER)
                .subject(roundId)
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key())
                .compact();
        return new SignedReplayLink(token, expiresAt, replayProperties.getPublicLinkTtl().toSeconds());
    }

    /**
     * Verifies a token and returns the single round it authorises.
     *
     * @throws RgsException {@link ErrorCode#REPLAY_LINK_EXPIRED} when the link simply ran out,
     *                      {@link ErrorCode#REPLAY_LINK_INVALID} for anything else - wrong signature,
     *                      wrong issuer, a player JWT presented here, or plain garbage
     */
    public VerifiedReplayLink verify(String token) {
        if (token == null || token.isBlank()) {
            throw new RgsException(ErrorCode.REPLAY_LINK_INVALID, "Replay link is missing its token");
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key())
                    .requireIssuer(ISSUER)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String roundId = claims.getSubject();
            if (roundId == null || roundId.isBlank()) {
                throw new RgsException(ErrorCode.REPLAY_LINK_INVALID,
                        "Replay link does not name a round");
            }
            Date expiration = claims.getExpiration();
            return new VerifiedReplayLink(roundId,
                    expiration == null ? null : expiration.toInstant());
        } catch (ExpiredJwtException ex) {
            // Its own code, and its own page: an expired proof link is spent for good, not retryable.
            throw new RgsException(ErrorCode.REPLAY_LINK_EXPIRED,
                    "This replay link has expired");
        } catch (JwtException | IllegalArgumentException ex) {
            // No echo of the token or the parser's complaint - a stranger holding a bad link learns only
            // that it is bad, which is all they are owed.
            log.info("Public replay token rejected: {}", ex.getClass().getSimpleName());
            throw new RgsException(ErrorCode.REPLAY_LINK_INVALID,
                    "This replay link is not valid");
        }
    }

    /**
     * HMAC-SHA256 of {@link #KEY_PURPOSE} under the configured JWT secret: one secret to operate, two
     * keys that cannot verify each other's tokens.
     */
    private SecretKey key() {
        SecretKey local = cachedKey;
        if (local == null) {
            String secret = securityProperties.getJwtSecret();
            if (secret == null || secret.length() < 32) {
                throw new IllegalStateException("rgs.security.jwt-secret must be >= 32 chars");
            }
            try {
                Mac mac = Mac.getInstance(HMAC_ALGORITHM);
                mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
                local = Keys.hmacShaKeyFor(mac.doFinal(KEY_PURPOSE.getBytes(StandardCharsets.UTF_8)));
            } catch (GeneralSecurityException ex) {
                throw new IllegalStateException("Cannot derive public replay signing key", ex);
            }
            cachedKey = local;
        }
        return local;
    }
}
