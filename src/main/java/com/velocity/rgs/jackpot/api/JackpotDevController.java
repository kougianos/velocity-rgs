package com.velocity.rgs.jackpot.api;

import com.velocity.rgs.common.error.ErrorCode;
import com.velocity.rgs.common.error.RgsException;
import com.velocity.rgs.config.PlayerContext;
import com.velocity.rgs.jackpot.JackpotService;
import com.velocity.rgs.jackpot.domain.JackpotTier;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Arms the caller's next spin to win a jackpot (§2). Demo mode only.
 *
 * <p>The Mega is 1 in 10,000 spins, which is the correct number for a jackpot and a useless one for a
 * demonstration: nobody watches ten thousand spins. This makes the feature showable on command without
 * softening the odds the game actually runs.
 *
 * <p><b>Only the dice are rigged.</b> Everything downstream is the production path - the
 * compare-and-swap that stops a double award, the {@code jackpot_win} row, the wallet credit, the pool
 * reset to seed. A viewer watching this is watching the real mechanism.
 *
 * <p>Scoped to the caller's own token and mapped under {@code /api/v1/jackpot} rather than beside the
 * other dev endpoints, for the same reason {@code RgDevController} is: {@code /api/v1/dev/**} is an
 * anonymous path, and an unauthenticated endpoint that hands out jackpots is not a thing worth having
 * in any mode.
 */
@Slf4j
@ConditionalOnProperty(prefix = "rgs", name = "mode", havingValue = "demo", matchIfMissing = true)
@RestController
@RequestMapping("/api/v1/jackpot/dev")
@RequiredArgsConstructor
public class JackpotDevController {

    private final JackpotService jackpotService;
    private final PlayerContext playerContext;

    @PostMapping("/force-win")
    public ResponseEntity<ForceWinResponse> forceWin(@Valid @RequestBody ForceWinRequest request) {
        String playerId = playerContext.getPlayerId();
        if (playerId == null || playerId.isBlank()) {
            throw new RgsException(ErrorCode.AUTH_FAILED, "No authenticated player");
        }
        jackpotService.forceNextWin(playerId, request.tier());
        log.info("DEV jackpot armed player={} tier={}", playerId, request.tier());
        return ResponseEntity.ok(new ForceWinResponse(request.tier(),
                "Your next staked spin on a contributing game will win the "
                        + request.tier().label() + " jackpot."));
    }

    public record ForceWinRequest(@NotNull JackpotTier tier) {}

    public record ForceWinResponse(JackpotTier tier, String message) {}
}
