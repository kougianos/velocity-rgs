package com.velocity.rgs.audit.replay;

import com.velocity.rgs.common.error.ErrorCode;
import com.velocity.rgs.common.error.RgsException;
import com.velocity.rgs.config.PlayerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only round replay endpoint per A.16 / M6 Task 6.2. Guarded by the {@code ADMIN} role claim
 * carried in the JWT.
 */
@RestController
@RequestMapping("/api/v1/admin/replay")
@RequiredArgsConstructor
public class ReplayAdminController {

    private static final String ADMIN_ROLE = "ADMIN";

    private final ReplayService replayService;
    private final PlayerContext playerContext;

    @PostMapping("/{roundId}")
    public ResponseEntity<RoundReplayResult> replay(@PathVariable String roundId) {
        if (!playerContext.hasRole(ADMIN_ROLE)) {
            throw new RgsException(ErrorCode.FORBIDDEN_ACTION,
                    "Admin role required for round replay");
        }
        return ResponseEntity.ok(replayService.replay(roundId));
    }
}
