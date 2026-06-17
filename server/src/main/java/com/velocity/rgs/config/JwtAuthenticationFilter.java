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
import org.springframework.beans.factory.ObjectProvider;
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
    private final ObjectProvider<PlayerContext> contextProvider;
    private final ObjectMapper objectMapper;

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
            writeAuthError(response, "Missing bearer token");
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

            PlayerContext ctx = contextProvider.getObject();
            ctx.setPlayerId(claims.getSubject());
            ctx.setSessionId(claims.get("sid", String.class));
            ctx.setCurrency(claims.get("cur", String.class));
            Object roles = claims.get("roles");
            ctx.setRoles(roles instanceof List<?> list
                    ? list.stream().map(Object::toString).toList()
                    : List.of());
            ctx.setAuthenticated(true);

            MDC.put("playerId", ctx.getPlayerId());
            if (ctx.getSessionId() != null) {
                MDC.put("sessionId", ctx.getSessionId());
            }
            chain.doFilter(request, response);
        } catch (JwtException | IllegalArgumentException ex) {
            log.info("JWT authentication failed: {}", ex.getMessage());
            writeAuthError(response, "Invalid or expired token");
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

    private void writeAuthError(HttpServletResponse response, String message) throws IOException {
        String traceId = MDC.get("traceId");
        if (traceId == null) {
            traceId = UUID.randomUUID().toString();
        }
        ApiError body = new ApiError(
                ErrorCode.AUTH_FAILED.name(),
                message,
                ErrorCode.AUTH_FAILED.httpStatus().value(),
                traceId,
                Instant.now(),
                null
        );
        response.setStatus(ErrorCode.AUTH_FAILED.httpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
