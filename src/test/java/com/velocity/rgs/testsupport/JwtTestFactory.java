package com.velocity.rgs.testsupport;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

public final class JwtTestFactory {

    public static final String SECRET = "test-secret-test-secret-test-secret-32b";
    public static final String ISSUER = "velocity-rgs";

    private JwtTestFactory() {}

    public static String validToken(String playerId, String sessionId, String currency) {
        return buildToken(playerId, sessionId, currency, List.of(), Instant.now().plus(1, ChronoUnit.HOURS));
    }

    public static String adminToken(String playerId) {
        return buildToken(playerId, "s-admin", "EUR", List.of("ADMIN"), Instant.now().plus(1, ChronoUnit.HOURS));
    }

    public static String expiredToken(String playerId) {
        return buildToken(playerId, "s-old", "EUR", List.of(), Instant.now().minus(1, ChronoUnit.HOURS));
    }

    public static String tokenWithBadIssuer(String playerId) {
        SecretKey key = key();
        return Jwts.builder()
                .issuer("not-velocity")
                .subject(playerId)
                .claim("sid", "s-1")
                .claim("cur", "EUR")
                .issuedAt(java.util.Date.from(Instant.now()))
                .expiration(java.util.Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .signWith(key)
                .compact();
    }

    private static String buildToken(String playerId, String sessionId, String currency,
                                     List<String> roles, Instant expiry) {
        SecretKey key = key();
        return Jwts.builder()
                .issuer(ISSUER)
                .subject(playerId)
                .claims(Map.of("sid", sessionId, "cur", currency, "roles", roles))
                .issuedAt(java.util.Date.from(Instant.now()))
                .expiration(java.util.Date.from(expiry))
                .signWith(key)
                .compact();
    }

    private static SecretKey key() {
        return Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    }
}
