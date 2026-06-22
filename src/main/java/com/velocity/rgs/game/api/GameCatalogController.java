package com.velocity.rgs.game.api;

import com.velocity.rgs.math.config.GameCatalogRegistry;
import com.velocity.rgs.math.config.GameDefinition;
import com.velocity.rgs.math.config.GamePresentation;
import com.velocity.rgs.math.config.SlotMathDefinition;
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
 * Public, read-only game catalog used by the lobby and game pages (A.5). It is the single source of truth
 * for everything the browser client needs to render a game: presentation (title, copy, theme, per-symbol
 * glyphs), the grid shape and paylines used to draw and highlight the reels, and the headline math facts a
 * player cares about (declared RTP, max-win ceiling, free-spin award, buy cost). Reel strips and the pay
 * table stay server-side. Anonymous so the lobby can load before authentication.
 */
@RestController
@RequestMapping("/api/v1/games")
@RequiredArgsConstructor
public class GameCatalogController {

    private final GameCatalogRegistry catalog;

    @GetMapping
    public ResponseEntity<List<GameSummary>> list() {
        List<GameSummary> games = catalog.all().stream()
                .map(GameCatalogController::toSummary)
                .toList();
        return ResponseEntity.ok(games);
    }

    private static GameSummary toSummary(GameDefinition game) {
        SlotMathDefinition math = game.math();
        GamePresentation p = game.presentation();
        return GameSummary.builder()
                .gameId(math.gameId())
                .mathVersion(math.mathVersion())
                // Presentation
                .title(p.title())
                .tagline(p.tagline())
                .description(p.description())
                .logo(p.logo())
                .theme(p.theme())
                .volatility(p.volatility())
                .spinDurationMillis(p.spinDurationMillis())
                .info(p.info())
                // Headline math facts
                .targetRtp(math.targetRtp())
                .maxWinMultiplier(math.limits().maxWinPerRoundMultiplier())
                // Staking — the client renders these as the bet selector; the server re-validates every spin
                .betValues(math.betConfig().values())
                .defaultBet(math.betConfig().defaultBet())
                .minBet(math.betConfig().minBet())
                .maxBet(math.betConfig().maxBet())
                .freeSpinsAwarded(math.scatterTriggers().freeSpinsAwarded())
                .freeSpinsBuyCostMultiplier(buyCost(math, BonusBuyType.FREE_SPINS_BUY))
                .pickCollectTriggerOneInN(math.pickCollect().triggerOneInN())
                // Layout the client needs to draw and highlight the reels
                .rows(math.grid().rows())
                .cols(math.grid().cols())
                .paylines(math.paylines().stream()
                        .map(pl -> new PaylineView(pl.id(), pl.coords()))
                        .toList())
                .symbols(toSymbolViews(game))
                .build();
    }

    /** Merge each math symbol id with its presentation glyph/name so the client can render the grid. */
    private static List<SymbolView> toSymbolViews(GameDefinition game) {
        GamePresentation p = game.presentation();
        return game.math().symbols().stream()
                .map(s -> {
                    GamePresentation.SymbolDisplay d = p.symbols().get(s.id());
                    String glyph = d != null ? d.glyph() : s.name();
                    String name = d != null ? d.name() : s.name();
                    return new SymbolView(s.id(), glyph, name);
                })
                .toList();
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
            String title,
            String tagline,
            String description,
            String logo,
            String theme,
            String volatility,
            int spinDurationMillis,
            GamePresentation.GameInfo info,
            BigDecimal targetRtp,
            int maxWinMultiplier,
            List<BigDecimal> betValues,
            BigDecimal defaultBet,
            BigDecimal minBet,
            BigDecimal maxBet,
            int freeSpinsAwarded,
            BigDecimal freeSpinsBuyCostMultiplier,
            int pickCollectTriggerOneInN,
            int rows,
            int cols,
            List<PaylineView> paylines,
            List<SymbolView> symbols
    ) {}

    /** A payline as the client draws it: its id and ordered {@code [row, col]} coordinates. */
    public record PaylineView(int id, int[][] coords) {}

    /** A reel symbol as the client renders it: math id plus the display glyph and friendly name. */
    public record SymbolView(int id, String glyph, String name) {}
}
