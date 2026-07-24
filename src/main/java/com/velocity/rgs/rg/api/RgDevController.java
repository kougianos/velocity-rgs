package com.velocity.rgs.rg.api;

import com.velocity.rgs.common.error.ErrorCode;
import com.velocity.rgs.common.error.RgsException;
import com.velocity.rgs.config.PlayerContext;
import com.velocity.rgs.rg.RgPolicyService;
import com.velocity.rgs.rg.domain.RgStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lifts the authenticated player's Responsible Gaming state, for demos only.
 *
 * <p>Self-exclusion is final and a cool-off is measured in hours, both correctly - which makes the
 * feature undemonstrable more than once without an escape hatch. Rather than weakening either rule to
 * suit a demo, the reset lives here: a separate controller, gated on {@code rgs.mode=demo} exactly as
 * {@code DevTokenController} is, and absent from the bean graph in any other mode. The rule stays
 * absolute where it counts.
 *
 * <p>Deliberately still scoped to the caller's own token. Even in demo mode there is no endpoint for
 * lifting somebody else's self-exclusion.
 *
 * <p>Mapped under {@code /api/v1/rg} rather than alongside the other dev endpoints, and that placement
 * is load-bearing: {@code /api/v1/dev/**} is an anonymous path, so the JWT filter never runs for it and
 * a reset living there would have no authenticated player to scope itself to. An unauthenticated
 * endpoint that lifts self-exclusion is not a thing worth having, in any mode.
 */
@Slf4j
@ConditionalOnProperty(prefix = "rgs", name = "mode", havingValue = "demo", matchIfMissing = true)
@RestController
@RequestMapping("/api/v1/rg/dev")
@RequiredArgsConstructor
public class RgDevController {

    private final RgPolicyService rgPolicyService;
    private final PlayerContext playerContext;

    @PostMapping("/reset")
    public ResponseEntity<RgStatus> reset() {
        String playerId = playerContext.getPlayerId();
        if (playerId == null || playerId.isBlank()) {
            throw new RgsException(ErrorCode.AUTH_FAILED, "No authenticated player");
        }
        log.info("DEV RG reset requested player={}", playerId);
        rgPolicyService.resetForDemo(playerId);
        return ResponseEntity.ok(rgPolicyService.status(playerId, playerContext.getCurrency()));
    }
}
