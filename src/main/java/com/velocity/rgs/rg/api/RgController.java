package com.velocity.rgs.rg.api;

import com.velocity.rgs.common.error.ErrorCode;
import com.velocity.rgs.common.error.RgsException;
import com.velocity.rgs.config.PlayerContext;
import com.velocity.rgs.rg.RgPolicyService;
import com.velocity.rgs.rg.domain.RgStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * The player's own Responsible Gaming surface (§4.2).
 *
 * <p>Every endpoint acts on the authenticated player and takes no player id, which is the only safe
 * shape for this: an endpoint that accepted one would be an endpoint for setting somebody else's
 * limits, or lifting them.
 *
 * <p>No {@code Idempotency-Key} here, unlike the game endpoints. These are not money movements and all
 * of them are naturally idempotent - setting a limit twice sets it once, and cool-off and
 * self-exclusion both re-apply to the same state.
 */
@RestController
@RequestMapping("/api/v1/rg")
@RequiredArgsConstructor
public class RgController {

    private final RgPolicyService rgPolicyService;
    private final PlayerContext playerContext;

    @GetMapping("/status")
    public ResponseEntity<RgStatus> status() {
        return ResponseEntity.ok(rgPolicyService.status(requirePlayerId(), currency()));
    }

    @PutMapping("/limits")
    public ResponseEntity<RgStatus> setLimits(@Valid @RequestBody SetLimitsRequest request) {
        return ResponseEntity.ok(rgPolicyService.setLimits(requirePlayerId(), currency(),
                request.sessionLimitMinutes(), request.lossLimit(), request.wagerLimit(),
                request.realityCheckMinutes()));
    }

    @PostMapping("/cool-off")
    public ResponseEntity<RgStatus> coolOff(@Valid @RequestBody CoolOffRequest request) {
        return ResponseEntity.ok(
                rgPolicyService.coolOff(requirePlayerId(), currency(), request.hours()));
    }

    /**
     * Self-exclusion. Takes a typed confirmation rather than an empty body, so the single most
     * consequential call in the API cannot be made by a stray click or a retried fetch.
     */
    @PostMapping("/self-exclude")
    public ResponseEntity<RgStatus> selfExclude(@Valid @RequestBody SelfExcludeRequest request) {
        if (!"SELF-EXCLUDE".equals(request.confirm())) {
            throw new RgsException(ErrorCode.VALIDATION_ERROR,
                    "Self-exclusion requires confirm=\"SELF-EXCLUDE\"");
        }
        return ResponseEntity.ok(rgPolicyService.selfExclude(requirePlayerId(), currency()));
    }

    @PostMapping("/reality-check/ack")
    public ResponseEntity<RgStatus> acknowledgeRealityCheck() {
        return ResponseEntity.ok(rgPolicyService.acknowledgeRealityCheck(requirePlayerId(), currency()));
    }

    /** Null on any field leaves that limit unchanged; the service caps how loose one may be set. */
    public record SetLimitsRequest(
            @Min(1) Integer sessionLimitMinutes,
            BigDecimal lossLimit,
            BigDecimal wagerLimit,
            @Min(1) Integer realityCheckMinutes) {}

    public record CoolOffRequest(@NotNull @Min(1) @Max(720) Integer hours) {}

    public record SelfExcludeRequest(@NotNull String confirm) {}

    private String requirePlayerId() {
        String playerId = playerContext.getPlayerId();
        if (playerId == null || playerId.isBlank()) {
            throw new RgsException(ErrorCode.AUTH_FAILED, "No authenticated player");
        }
        return playerId;
    }

    private String currency() {
        return playerContext.getCurrency();
    }
}
