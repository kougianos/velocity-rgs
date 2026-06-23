package com.velocity.rgs.roulette.api;

import com.velocity.rgs.common.error.ErrorCode;
import com.velocity.rgs.common.error.RgsException;
import com.velocity.rgs.common.idempotency.Idempotent;
import com.velocity.rgs.config.PlayerContext;
import com.velocity.rgs.roulette.service.RouletteEngineService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public Roulette REST surface. Mirrors the slot controller's contract: every mutating endpoint requires an
 * authenticated JWT (populated into {@link PlayerContext} by {@code JwtAuthenticationFilter}) and the spin
 * carries a non-empty {@code Idempotency-Key} header (enforced by {@code IdempotencyAspect}). Roulette is
 * stateless per round, so there are only {@code /init} and {@code /spin}.
 */
@RestController
@RequestMapping("/api/v1/roulette")
@RequiredArgsConstructor
public class RouletteController {

    private final RouletteEngineService rouletteEngineService;
    private final PlayerContext playerContext;

    @PostMapping("/init")
    public ResponseEntity<RouletteInitResponse> init(@Valid @RequestBody RouletteInitRequest request) {
        String playerId = requirePlayerId();
        return ResponseEntity.ok(rouletteEngineService.init(request, playerId, playerContext.getCurrency()));
    }

    @PostMapping("/spin")
    @Idempotent(scope = "roulette:spin:{playerId}", ttlHours = 24)
    public ResponseEntity<RouletteSpinResponse> spin(@Valid @RequestBody RouletteSpinRequest request) {
        String playerId = requirePlayerId();
        return ResponseEntity.ok(rouletteEngineService.spin(request, playerId));
    }

    private String requirePlayerId() {
        String playerId = playerContext.getPlayerId();
        if (playerId == null || playerId.isBlank()) {
            throw new RgsException(ErrorCode.AUTH_FAILED, "No authenticated player");
        }
        return playerId;
    }
}
