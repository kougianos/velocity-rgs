package com.velocity.rgs.slot.api;

import com.velocity.rgs.common.error.ErrorCode;
import com.velocity.rgs.common.error.RgsException;
import com.velocity.rgs.common.idempotency.Idempotent;
import com.velocity.rgs.config.PlayerContext;
import com.velocity.rgs.slot.service.SlotEngineService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public Slot Game REST surface (A.7 / M5 Task 5.9). All mutating endpoints require an authenticated
 * JWT (populated into {@link PlayerContext} by {@code JwtAuthenticationFilter}) and a non-empty
 * {@code Idempotency-Key} header (enforced by {@code IdempotencyAspect}).
 */
@RestController
@RequestMapping("/api/v1/slot")
@RequiredArgsConstructor
public class SlotGameController {

    private final SlotEngineService slotEngineService;
    private final PlayerContext playerContext;

    @PostMapping("/init")
    public ResponseEntity<SlotInitResponse> init(@Valid @RequestBody SlotInitRequest request) {
        String playerId = requirePlayerId();
        return ResponseEntity.ok(slotEngineService.init(request, playerId, playerContext.getCurrency()));
    }

    @PostMapping("/spin")
    @Idempotent(scope = "slot:spin:{playerId}", ttlHours = 24)
    public ResponseEntity<SpinResponse> spin(@Valid @RequestBody SpinRequest request) {
        String playerId = requirePlayerId();
        return ResponseEntity.ok(slotEngineService.spin(request, playerId));
    }

    @PostMapping("/feature/start")
    @Idempotent(scope = "slot:feature-start:{playerId}", ttlHours = 24)
    public ResponseEntity<FeatureStartResponse> startFeature(@Valid @RequestBody FeatureStartRequest request) {
        String playerId = requirePlayerId();
        return ResponseEntity.ok(slotEngineService.startFeature(request, playerId));
    }

    @PostMapping("/feature/buy")
    @Idempotent(scope = "slot:feature-buy:{playerId}", ttlHours = 24)
    public ResponseEntity<FeatureBuyResponse> buyFeature(@Valid @RequestBody FeatureBuyRequest request) {
        String playerId = requirePlayerId();
        return ResponseEntity.ok(slotEngineService.buyFeature(request, playerId, null));
    }

    @PostMapping("/feature/pick")
    @Idempotent(scope = "slot:feature-pick:{playerId}", ttlHours = 24)
    public ResponseEntity<FeaturePickResponse> pickFeature(@Valid @RequestBody FeaturePickRequest request) {
        String playerId = requirePlayerId();
        return ResponseEntity.ok(slotEngineService.pickFeature(request, playerId));
    }

    private String requirePlayerId() {
        String playerId = playerContext.getPlayerId();
        if (playerId == null || playerId.isBlank()) {
            throw new RgsException(ErrorCode.AUTH_FAILED, "No authenticated player");
        }
        return playerId;
    }
}
