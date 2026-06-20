package com.velocity.rgs.qa.dev;

import com.velocity.rgs.config.SecurityProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Demo-only JWT minting helper (M7 Task 7.2, Appendix A.20). Registered only when
 * {@code rgs.mode=demo} (the default), and the path {@code /api/v1/dev/token} is whitelisted in
 * {@link SecurityProperties#getPublicPaths()} so callers can obtain a token without having one yet.
 */
@Slf4j
@ConditionalOnProperty(prefix = "rgs", name = "mode", havingValue = "demo", matchIfMissing = true)
@RestController
@RequestMapping("/api/v1/dev")
@RequiredArgsConstructor
public class DevTokenController {

    private final SecurityProperties securityProperties;

    @PostMapping("/token")
    public ResponseEntity<TokenResponse> mint(@Valid @RequestBody TokenRequest request) {
        long ttlMinutes = request.ttlMinutes() == null || request.ttlMinutes() <= 0 ? 60L : request.ttlMinutes();
        Instant now = Instant.now();
        Instant expiry = now.plus(ttlMinutes, ChronoUnit.MINUTES);
        List<String> roles = request.roles() == null ? List.of("PLAYER") : List.copyOf(request.roles());

        SecretKey key = Keys.hmacShaKeyFor(securityProperties.getJwtSecret().getBytes(StandardCharsets.UTF_8));
        String token = Jwts.builder()
                .issuer(securityProperties.getJwtIssuer())
                .subject(request.playerId())
                .claims(Map.of(
                        "sid", request.sessionId(),
                        "cur", request.currency(),
                        "roles", roles))
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(key)
                .compact();

        log.info("Issued dev JWT playerId={} sessionId={} ttlMinutes={} roles={}",
                request.playerId(), request.sessionId(), ttlMinutes, roles);
        return ResponseEntity.ok(TokenResponse.builder()
                .token(token)
                .expiresAt(expiry)
                .build());
    }

    public record TokenRequest(
            @NotBlank String playerId,
            @NotBlank String sessionId,
            @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "currency must be a 3-letter ISO code") String currency,
            List<String> roles,
            @Min(1) Long ttlMinutes
    ) {}

    @Builder
    public record TokenResponse(String token, Instant expiresAt) {}
}
