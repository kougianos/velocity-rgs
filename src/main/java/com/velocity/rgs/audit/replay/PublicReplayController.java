package com.velocity.rgs.audit.replay;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The anonymous half of §3.1: serves one round's reconstruction to whoever holds a valid link.
 *
 * <p>Note what this endpoint does <em>not</em> accept - a round id. The only input is the token, and the
 * round comes back out of it after the signature verifies
 * ({@link PublicReplayTokenService#verify(String)}). There is consequently nothing in the request a
 * holder could edit to reach a different round: the scoping is structural rather than a check that could
 * be forgotten. The response is redacted to match (see {@link PublicRoundReplay}).
 *
 * <p>Unauthenticated by design, so it is registered under {@code /api/v1/public/**}, which
 * {@code rgs.security.public-paths} exempts from the JWT filter in every run mode - a proof link that
 * only worked in demo mode would not be worth minting.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/public/replay")
@RequiredArgsConstructor
public class PublicReplayController {

    private final PublicReplayTokenService tokenService;
    private final ReplayService replayService;

    @GetMapping("/{token}")
    public ResponseEntity<PublicRoundReplay> replay(@PathVariable String token) {
        PublicReplayTokenService.VerifiedReplayLink link = tokenService.verify(token);
        RoundReplayResult result = replayService.replay(link.roundId());
        log.info("PUBLIC replay served roundId={} matched={}", link.roundId(), result.matrixMatches());
        return ResponseEntity.ok()
                // The verdict is re-derived per request and the payload is round-specific; neither
                // belongs in a shared cache between one link holder and the next.
                .cacheControl(CacheControl.noStore())
                .body(PublicRoundReplay.from(result, link.expiresAt()));
    }
}
