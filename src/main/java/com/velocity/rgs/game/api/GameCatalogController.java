package com.velocity.rgs.game.api;

import com.velocity.rgs.math.config.SlotMathDefinition;
import com.velocity.rgs.math.config.SlotMathRegistry;
import com.velocity.rgs.math.domain.BonusBuyType;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * Public, read-only game catalog used by the lobby/landing page to render the list of playable games
 * (A.5). Returns the headline math facts a player cares about when choosing a game — declared RTP, the
 * max-win ceiling (which differs per game: e.g. 2,000x vs 25,000x) and which features are available —
 * without exposing reel strips or pay tables. Anonymous so the lobby can load before authentication.
 */
@RestController
@RequestMapping("/api/v1/games")
@RequiredArgsConstructor
public class GameCatalogController {

    private final SlotMathRegistry registry;

    @GetMapping
    public ResponseEntity<List<GameSummary>> list() {
        List<GameSummary> games = registry.all().stream()
                .map(GameCatalogController::toSummary)
                .toList();
        return ResponseEntity.ok(games);
    }

    private static GameSummary toSummary(SlotMathDefinition math) {
        return GameSummary.builder()
                .gameId(math.gameId())
                .mathVersion(math.mathVersion())
                .targetRtp(math.targetRtp())
                .maxWinMultiplier(math.limits().maxWinPerRoundMultiplier())
                .freeSpinsAwarded(math.scatterTriggers().freeSpinsAwarded())
                .freeSpinsBuyCostMultiplier(buyCost(math, BonusBuyType.FREE_SPINS_BUY))
                .pickCollectTriggerOneInN(math.pickCollect().triggerOneInN())
                .build();
    }

    private static BigDecimal buyCost(SlotMathDefinition math, BonusBuyType type) {
        return math.bonusBuyOptions().stream()
                .filter(o -> o.buyType() == type)
                .map(o -> o.costMultiplier())
                .findFirst()
                .orElse(null);
    }

    @Builder
    public record GameSummary(
            String gameId,
            String mathVersion,
            BigDecimal targetRtp,
            int maxWinMultiplier,
            int freeSpinsAwarded,
            BigDecimal freeSpinsBuyCostMultiplier,
            int pickCollectTriggerOneInN
    ) {}
}
