package com.velocity.rgs.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocity.rgs.common.error.ApiError;
import com.velocity.rgs.common.error.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";
    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private final SecurityProperties properties;
    private final ObjectMapper objectMapper;
    private final TokenDenylist tokenDenylist;

    private volatile SecretKey cachedKey;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (isPublic(request)) {
            chain.doFilter(request, response);
            return;
        }

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER)) {
            writeError(response, ErrorCode.AUTH_FAILED, "Missing bearer token");
            return;
        }

        String token = header.substring(BEARER.length()).trim();
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key())
                    .requireIssuer(properties.getJwtIssuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

                // Signature, issuer and expiry all passed, and the token is still refused. That is the
                // entire point of a denylist: self-exclusion has to close an account now, not whenever
                // the token in the player's open tab happens to run out.
                String denial = tokenDenylist.denialReason(claims.getId());
                if (denial != null) {
                    log.info("Severed token refused playerId={} jti={} reason={}",
                            claims.getSubject(), claims.getId(), denial);
                    writeError(response, ErrorCode.RG_SELF_EXCLUDED,
                            "This account is self-excluded and cannot be used to play");
                    return;
                }

                String playerId = claims.getSubject();
                String sessionId = claims.get("sid", String.class);
                String currency = claims.get("cur", String.class);
                Object rolesRaw = claims.get("roles");
                List<String> roles = rolesRaw instanceof List<?> list
                    ? list.stream().map(Object::toString).toList()
                    : List.of();

                request.setAttribute(PlayerContext.ATTR_PLAYER_ID, playerId);
                request.setAttribute(PlayerContext.ATTR_SESSION_ID, sessionId);
                request.setAttribute(PlayerContext.ATTR_CURRENCY, currency);
                request.setAttribute(PlayerContext.ATTR_ROLES, roles);
                request.setAttribute(PlayerContext.ATTR_AUTHENTICATED, Boolean.TRUE);

                MDC.put("playerId", playerId);
                if (sessionId != null) {
                MDC.put("sessionId", sessionId);
            }
            chain.doFilter(request, response);
        } catch (JwtException | IllegalArgumentException ex) {
            log.info("JWT authentication failed: {}", ex.getMessage());
            writeError(response, ErrorCode.AUTH_FAILED, "Invalid or expired token");
        }
    }

    private SecretKey key() {
        SecretKey local = cachedKey;
        if (local == null) {
            String secret = properties.getJwtSecret();
            if (secret == null || secret.length() < 32) {
                throw new IllegalStateException("rgs.security.jwt-secret must be >= 32 chars");
            }
            local = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
            cachedKey = local;
        }
        return local;
    }

    private boolean isPublic(HttpServletRequest req) {
        String path = req.getRequestURI();
        return properties.getPublicPaths().stream().anyMatch(p -> MATCHER.match(p, path));
    }

    /**
     * Refusals from the filter carry the same {@link ApiError} shape as refusals from a controller, so a
     * client parses one thing. The code varies: a token that cannot be trusted is
     * {@code AUTH_FAILED}, and a token that is perfectly valid but belongs to a closed account is
     * {@code RG_SELF_EXCLUDED} - which the game client already knows how to render, and which a
     * blanket 401 would have made indistinguishable from an expired session.
     */
    private void writeError(HttpServletResponse response, ErrorCode code, String message)
            throws IOException {
        String traceId = MDC.get("traceId");
        if (traceId == null) {
            traceId = UUID.randomUUID().toString();
        }
        ApiError body = new ApiError(
                code.name(),
                message,
                code.httpStatus().value(),
                traceId,
                Instant.now(),
                null,
                null
        );
        response.setStatus(code.httpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
