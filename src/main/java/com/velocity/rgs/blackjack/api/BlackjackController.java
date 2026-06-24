package com.velocity.rgs.blackjack.api;

import com.velocity.rgs.blackjack.service.BlackjackEngineService;
import com.velocity.rgs.common.error.ErrorCode;
import com.velocity.rgs.common.error.RgsException;
import com.velocity.rgs.common.idempotency.Idempotent;
import com.velocity.rgs.config.PlayerContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public Blackjack REST surface. Unlike the one-shot slot/roulette controllers, a blackjack round spans
 * multiple calls: {@code /init} (resume or prepare), {@code /deal} (start a round), and {@code /action}
 * (hit/stand/double/split/insurance). Every mutating endpoint requires an authenticated JWT (populated into
 * {@link PlayerContext} by {@code JwtAuthenticationFilter}); {@code /deal} and {@code /action} carry a
 * non-empty {@code Idempotency-Key} header (enforced by {@code IdempotencyAspect}).
 */
@RestController
@RequestMapping("/api/v1/blackjack")
@RequiredArgsConstructor
public class BlackjackController {

    private final BlackjackEngineService blackjackEngineService;
    private final PlayerContext playerContext;

    @PostMapping("/init")
    public ResponseEntity<BlackjackInitResponse> init(@Valid @RequestBody BlackjackInitRequest request) {
        String playerId = requirePlayerId();
        return ResponseEntity.ok(blackjackEngineService.init(request, playerId, playerContext.getCurrency()));
    }

    @PostMapping("/deal")
    @Idempotent(scope = "blackjack:deal:{playerId}", ttlHours = 24)
    public ResponseEntity<BlackjackRoundResponse> deal(@Valid @RequestBody BlackjackDealRequest request) {
        String playerId = requirePlayerId();
        return ResponseEntity.ok(blackjackEngineService.deal(request, playerId));
    }

    @PostMapping("/action")
    @Idempotent(scope = "blackjack:action:{playerId}", ttlHours = 24)
    public ResponseEntity<BlackjackRoundResponse> action(@Valid @RequestBody BlackjackActionRequest request) {
        String playerId = requirePlayerId();
        return ResponseEntity.ok(blackjackEngineService.action(request, playerId));
    }

    private String requirePlayerId() {
        String playerId = playerContext.getPlayerId();
        if (playerId == null || playerId.isBlank()) {
            throw new RgsException(ErrorCode.AUTH_FAILED, "No authenticated player");
        }
        return playerId;
    }
}
