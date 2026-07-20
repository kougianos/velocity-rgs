package com.velocity.rgs.audit.replay;

import com.velocity.rgs.common.error.ErrorCode;
import com.velocity.rgs.common.error.RgsException;
import com.velocity.rgs.config.PlayerContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.time.Instant;

/**
 * Admin-only round replay endpoints per A.16 / M6 Task 6.2. Guarded by the {@code ADMIN} role claim
 * carried in the JWT.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/replay")
@RequiredArgsConstructor
public class ReplayAdminController {

    private static final String ADMIN_ROLE = "ADMIN";

    private final ReplayService replayService;
    private final PublicReplayTokenService tokenService;
    private final PlayerContext playerContext;

    @PostMapping("/{roundId}")
    public ResponseEntity<RoundReplayResult> replay(@PathVariable String roundId) {
        requireAdmin();
        return ResponseEntity.ok(replayService.replay(roundId));
    }

    /**
     * Mints a public, signed, expiring link to one round (§3.1).
     *
     * <p>The round is replayed before the link is signed, and the replay's verdict is returned with it.
     * That is the point rather than a precaution: a link can only be minted for a round that reconstructs
     * right now, so what gets shared is always a proof that held at the moment it was created - never a
     * URL that turns out to 404 or to reconstruct badly in front of whoever was sent it.
     */
    @PostMapping("/{roundId}/share")
    public ResponseEntity<ShareLinkResponse> share(@PathVariable String roundId) {
        requireAdmin();
        RoundReplayResult verified = replayService.replay(roundId);
        PublicReplayTokenService.SignedReplayLink link = tokenService.mint(roundId);

        String url = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/r/{token}")
                .buildAndExpand(link.token())
                .toUriString();

        log.info("ADMIN shareReplay admin={} roundId={} matched={} expiresAt={}",
                playerContext.getPlayerId(), roundId, verified.matrixMatches(), link.expiresAt());

        return ResponseEntity.ok(new ShareLinkResponse(
                roundId, url, link.token(), link.expiresAt(), link.ttlSeconds(),
                verified.matrixMatches(), verified.totalWinMatches()));
    }

    private void requireAdmin() {
        if (!playerContext.hasRole(ADMIN_ROLE)) {
            throw new RgsException(ErrorCode.FORBIDDEN_ACTION,
                    "Admin role required for round replay");
        }
    }

    /**
     * @param verifiedMatrixMatches the verdict at mint time - what the sharer is putting their name to
     */
    public record ShareLinkResponse(
            String roundId,
            String url,
            String token,
            Instant expiresAt,
            long ttlSeconds,
            boolean verifiedMatrixMatches,
            boolean verifiedTotalWinMatches
    ) {}
}
