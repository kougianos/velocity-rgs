package com.velocity.rgs.catalog;

import com.velocity.rgs.roulette.config.RouletteBetTypeConfig;
import com.velocity.rgs.roulette.config.RouletteGameDefinition;
import com.velocity.rgs.roulette.config.RouletteCatalogRegistry;
import com.velocity.rgs.roulette.config.RouletteMathDefinition;
import com.velocity.rgs.roulette.config.RoulettePresentation;
import com.velocity.rgs.roulette.domain.RouletteBetKind;
import com.velocity.rgs.roulette.engine.RouletteWheel;
import com.velocity.rgs.slot.math.config.GameCatalogRegistry;
import com.velocity.rgs.slot.math.config.GameDefinition;
import com.velocity.rgs.slot.math.config.GamePresentation;
import com.velocity.rgs.slot.math.config.SlotMathDefinition;
import com.velocity.rgs.slot.math.domain.BonusBuyType;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Public, read-only game catalog used by the lobby and game pages — the single source of truth for everything
 * the browser client needs to render a game. It serves <b>both</b> game types from one endpoint, each tagged
 * with a {@link GameType}: slot entries carry the flat reel facts (grid, paylines, symbols, free-spins) the
 * existing client already reads; roulette entries carry a nested {@code roulette} object (wheel pockets, bet
 * types, limits). Common fields (title, copy, theme, RTP, bet values, info) are shared and flat. Reel strips,
 * pay tables and the wheel's RNG stay server-side. Anonymous so the lobby loads before authentication.
 */
@RestController
@RequestMapping("/api/v1/games")
@RequiredArgsConstructor
public class GameCatalogController {

    private final GameCatalogRegistry slotCatalog;
    private final RouletteCatalogRegistry rouletteCatalog;

    @GetMapping
    public ResponseEntity<List<GameSummary>> list() {
        List<GameSummary> games = new ArrayList<>();
        slotCatalog.all().forEach(g -> games.add(toSlotSummary(g)));
        rouletteCatalog.all().forEach(g -> games.add(toRouletteSummary(g)));
        return ResponseEntity.ok(games);
    }

    // ------------------------------------------------------------------ slot

    private static GameSummary toSlotSummary(GameDefinition game) {
        SlotMathDefinition math = game.math();
        GamePresentation p = game.presentation();
        return GameSummary.builder()
                .gameId(math.gameId())
                .mathVersion(math.mathVersion())
                .gameType(GameType.SLOT)
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
                // Staking
                .betValues(math.betConfig().values())
                .defaultBet(math.betConfig().defaultBet())
                .minBet(math.betConfig().minBet())
                .maxBet(math.betConfig().maxBet())
                .freeSpinsAwarded(math.scatterTriggers().freeSpinsAwarded())
                .freeSpinsBuyCostMultiplier(buyCost(math, BonusBuyType.FREE_SPINS_BUY))
                .pickCollectTriggerOneInN(math.pickCollect().triggerOneInN())
                // Reel layout
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

    // ------------------------------------------------------------------ roulette

    private static GameSummary toRouletteSummary(RouletteGameDefinition game) {
        RouletteMathDefinition math = game.math();
        RoulettePresentation p = game.presentation();
        return GameSummary.builder()
                .gameId(math.gameId())
                .mathVersion(math.mathVersion())
                .gameType(GameType.ROULETTE)
                .title(p.title())
                .tagline(p.tagline())
                .description(p.description())
                .logo(p.logo())
                .theme(p.theme())
                .volatility(p.volatility())
                .spinDurationMillis(p.spinDurationMillis())
                .info(p.info())
                .targetRtp(math.targetRtp())
                .betValues(math.betConfig().values())
                .defaultBet(math.betConfig().defaultBet())
                .minBet(math.betConfig().minBet())
                .maxBet(math.betConfig().maxBet())
                .roulette(toRouletteView(math))
                .build();
    }

    private static RouletteView toRouletteView(RouletteMathDefinition math) {
        List<PocketView> pockets = new ArrayList<>(math.pocketCount());
        for (int n = 0; n < math.pocketCount(); n++) {
            pockets.add(new PocketView(n, RouletteWheel.colorOf(n, math).name()));
        }
        int maxPayout = math.betTypes().stream().mapToInt(RouletteBetTypeConfig::payout).max().orElse(0);
        List<BetTypeView> betTypes = math.betTypes().stream()
                .map(bt -> new BetTypeView(bt.kind().name(), bt.payout(), label(bt.kind())))
                .toList();
        return RouletteView.builder()
                .variant(math.variant())
                .pocketCount(math.pocketCount())
                .maxPayoutMultiplier(maxPayout)
                .maxBetPerSpot(math.limits().maxBetPerSpot())
                .maxTotalBet(math.limits().maxTotalBet())
                .pockets(pockets)
                .betTypes(betTypes)
                .build();
    }

    /** Server-side friendly label per bet kind so the client renders the table without hardcoding labels. */
    private static String label(RouletteBetKind kind) {
        return switch (kind) {
            case STRAIGHT -> "Straight Up";
            case RED -> "Red";
            case BLACK -> "Black";
            case EVEN -> "Even";
            case ODD -> "Odd";
            case LOW -> "1–18";
            case HIGH -> "19–36";
            case DOZEN_1 -> "1st 12";
            case DOZEN_2 -> "2nd 12";
            case DOZEN_3 -> "3rd 12";
            case COLUMN_1 -> "Column 1";
            case COLUMN_2 -> "Column 2";
            case COLUMN_3 -> "Column 3";
        };
    }

    // ------------------------------------------------------------------ DTOs

    @Builder
    public record GameSummary(
            String gameId,
            String mathVersion,
            GameType gameType,
            String title,
            String tagline,
            String description,
            String logo,
            String theme,
            String volatility,
            int spinDurationMillis,
            GameInfo info,
            BigDecimal targetRtp,
            List<BigDecimal> betValues,
            BigDecimal defaultBet,
            BigDecimal minBet,
            BigDecimal maxBet,
            // Slot-only (null/0 for roulette)
            Integer maxWinMultiplier,
            Integer freeSpinsAwarded,
            BigDecimal freeSpinsBuyCostMultiplier,
            Integer pickCollectTriggerOneInN,
            Integer rows,
            Integer cols,
            List<PaylineView> paylines,
            List<SymbolView> symbols,
            // Roulette-only (null for slots)
            RouletteView roulette
    ) {}

    /** A payline as the client draws it: its id and ordered {@code [row, col]} coordinates. */
    public record PaylineView(int id, int[][] coords) {}

    /** A reel symbol as the client renders it: math id plus the display glyph and friendly name. */
    public record SymbolView(int id, String glyph, String name) {}

    /** Everything the client needs to draw a roulette table + wheel — all server-driven. */
    @Builder
    public record RouletteView(
            String variant,
            int pocketCount,
            int maxPayoutMultiplier,
            BigDecimal maxBetPerSpot,
            BigDecimal maxTotalBet,
            List<PocketView> pockets,
            List<BetTypeView> betTypes
    ) {}

    /** A single wheel pocket: its number and colour (RED/BLACK/GREEN). */
    public record PocketView(int number, String color) {}

    /** A bettable spot: the bet kind, its "to-one" payout, and a friendly label. */
    public record BetTypeView(String kind, int payout, String label) {}
}
