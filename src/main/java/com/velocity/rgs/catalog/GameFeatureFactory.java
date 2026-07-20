package com.velocity.rgs.catalog;

import com.velocity.rgs.blackjack.config.BlackjackMathDefinition;
import com.velocity.rgs.roulette.config.RouletteMathDefinition;
import com.velocity.rgs.slot.math.config.BonusBuyOption;
import com.velocity.rgs.slot.math.config.CascadeConfig;
import com.velocity.rgs.slot.math.config.CoinValueWeight;
import com.velocity.rgs.slot.math.config.RespinConfig;
import com.velocity.rgs.slot.math.config.RespinJackpot;
import com.velocity.rgs.slot.math.config.SlotMathDefinition;
import com.velocity.rgs.slot.math.config.WildFeatureConfig;
import com.velocity.rgs.slot.math.domain.BonusBuyType;
import com.velocity.rgs.slot.math.domain.ReelStripSet;
import com.velocity.rgs.slot.math.domain.WaysDirection;
import com.velocity.rgs.slot.math.domain.WinModel;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Turns a game's math config into the list of mechanics the lobby advertises.
 *
 * <p>Every card here is produced from the block that switches the mechanic on, and every number it
 * quotes is read from that block - the cascade ladder from {@code cascades.stepMultipliers}, the
 * jackpot tiers from {@code respins.jackpots}, the buy prices from {@code bonusBuyOptions}. Nothing is
 * transcribed. A game that does not configure a mechanic simply produces no card for it, which is why
 * the six slots each advertise a different set without anyone maintaining six lists.
 *
 * <p>The prose is written for a player; the numbers are the config. Where a fact makes a claim about
 * the platform rather than the game ("replays bit-exact", "each buy price is calibrated"), that claim
 * is one the test suite guards - {@code CascadeReplayIntegrationTest} and the {@code -Prtp} bonus-buy
 * verifications respectively. Claims nothing else asserts are deliberately absent.
 */
final class GameFeatureFactory {

    private GameFeatureFactory() {
    }

    // ------------------------------------------------------------------ slot

    static List<GameFeature> forSlot(SlotMathDefinition math) {
        List<GameFeature> features = new ArrayList<>();
        features.add(winModel(math));
        if (math.cascades().enabled()) {
            features.add(cascades(math.cascades()));
        }
        if (math.respins().enabled()) {
            features.add(holdSpin(math));
            if (!math.respins().jackpots().isEmpty()) {
                features.add(jackpots(math));
            }
        }
        if (math.wildFeatures().active()) {
            features.add(wilds(math.wildFeatures()));
        }
        features.add(freeSpins(math));
        if (math.pickCollect().organicTriggerEnabled()) {
            features.add(pickCollect(math));
        }
        features.add(powerBet(math));
        if (!math.bonusBuyOptions().isEmpty()) {
            features.add(bonusBuy(math));
        }
        return headlineFirst(features);
    }

    /**
     * Signature mechanics lead. Built in mechanical order above (win model, then features in the order
     * the engine applies them), which would open Dragon Hoard's card list on "20 Paylines" - true, and
     * the least interesting thing about it. A <em>stable</em> sort promotes the headline mechanics
     * without disturbing the order within either group, so a ways game still leads with its ways card.
     */
    private static List<GameFeature> headlineFirst(List<GameFeature> features) {
        return features.stream()
                .sorted(Comparator.comparing(GameFeature::headline).reversed())
                .toList();
    }

    /**
     * How the game turns a grid into wins. Only a ways game leads with this - "20 paylines" is the
     * assumption a player already brings, whereas "no paylines, every path pays" is the thing to say
     * first.
     */
    private static GameFeature winModel(SlotMathDefinition math) {
        int rows = math.grid().rows();
        int cols = math.grid().cols();
        if (math.winModel() == WinModel.WAYS) {
            int ways = (int) Math.pow(rows, cols);
            List<String> facts = new ArrayList<>();
            facts.add(count(ways) + " live paths across a " + cols + "×" + rows + " grid");
            facts.add("Ways are the product of per-reel hits, so a stacked symbol multiplies them");
            if (math.waysDirection() == WaysDirection.BOTH_WAYS) {
                facts.add("Pays both ways - runs are read from the left reel and the right");
            } else {
                facts.add("Left to right on adjacent reels, starting from reel 1");
            }
            facts.add("Wilds substitute only and never land on reel 1, so every run is anchored by a "
                    + "real symbol");
            return new GameFeature("WIN_MODEL", count(ways) + " Ways to Win", "🧭",
                    "There are no paylines. Every left-to-right path through the reels is live, so a "
                            + "symbol only has to land somewhere on each reel to pay.",
                    facts, true);
        }
        int lines = math.paylines().size();
        return new GameFeature("WIN_MODEL", lines + " Paylines", "📊",
                "Fixed lines with fixed coordinates, evaluated left to right on adjacent reels from "
                        + "the leftmost reel.",
                List.of(lines + " fixed lines on a " + cols + "×" + rows + " grid",
                        "Left to right on adjacent reels",
                        "A line pays once - the better of its wild run and its substituted run"),
                false);
    }

    private static GameFeature cascades(CascadeConfig cascades) {
        String ladder = cascades.stepMultipliers().stream().map(GameFeatureFactory::mult)
                .reduce((a, b) -> a + " → " + b).orElse("");
        return new GameFeature("CASCADES", "Cascading Reels", "🌊",
                "Winning symbols are struck from the board, the survivors drop into the gaps and fresh "
                        + "symbols fall in from the top - then the new grid is evaluated again. A single "
                        + "spin can pay many times over.",
                List.of("Multiplier ladder: " + ladder,
                        "Up to " + cascades.maxCascades() + " tumbles in one spin",
                        "The ladder climbs per tumble and then holds at its top step for the rest of "
                                + "the chain",
                        "Every drop is persisted in the round and replays bit-exact from its own RNG "
                                + "draws"),
                true);
    }

    private static GameFeature holdSpin(SlotMathDefinition math) {
        RespinConfig respins = math.respins();
        List<BigDecimal> values = respins.coinValues().stream()
                .map(CoinValueWeight::value).sorted().toList();
        String range = values.isEmpty() ? ""
                : mult(values.get(0)) + " - " + mult(values.get(values.size() - 1));
        return new GameFeature("HOLD_SPIN", "Hold & Spin", "🪙",
                "Land " + respins.triggerMinCount() + " or more coins and they lock where they fell. "
                        + "Only the cells that are not already holding a coin are re-drawn - and every "
                        + "new coin locks too, resetting the counter.",
                List.of("Triggered by " + respins.triggerMinCount() + "+ coins on the base grid",
                        respins.respinsAwarded() + " respins, reset back to " + respins.respinsAwarded()
                                + " on every catch",
                        "Coin values " + range + " the stake, drawn from a weighted ladder",
                        "Each respin is its own persisted round, so it replays independently of the "
                                + "spin that triggered it"),
                true);
    }

    /**
     * The jackpot ladder, listed in the order a player climbs it. A tier whose {@code minCoins} fills
     * the grid is the headline prize and is named as such rather than by its coin count.
     */
    private static GameFeature jackpots(SlotMathDefinition math) {
        int cells = math.grid().rows() * math.grid().cols();
        List<String> facts = math.respins().jackpots().stream()
                .sorted(Comparator.comparingInt(RespinJackpot::minCoins))
                .map(j -> j.minCoins() >= cells
                        ? j.tier() + " - " + mult(j.multiplier()) + " for a full " + cells + "-cell grid"
                        : j.tier() + " - " + mult(j.multiplier()) + " at " + j.minCoins() + " coins")
                .toList();
        RespinJackpot top = math.respins().jackpots().stream()
                .max(Comparator.comparingInt(RespinJackpot::minCoins)).orElseThrow();
        return new GameFeature("JACKPOTS", "Jackpot Tiers", "💎",
                "Hold enough coins when the feature settles and the round pays a jackpot on top of "
                        + "everything the coins themselves are worth. Fill every cell and the "
                        + top.tier() + " lands.",
                facts, true);
    }

    private static GameFeature wilds(WildFeatureConfig wilds) {
        List<String> names = new ArrayList<>();
        List<String> facts = new ArrayList<>();
        if (wilds.expanding()) {
            names.add("Expanding");
            facts.add("Expanding - one wild fills its entire reel");
        }
        if (wilds.sticky()) {
            names.add("Sticky");
            facts.add("Sticky - a wild holds its cell for " + wilds.stickySpins() + " further spins");
        }
        if (wilds.walking()) {
            names.add("Walking");
            facts.add("Walking - each spin it steps one reel left until it walks off the grid");
        }
        facts.add(scope(wilds));
        // Worth saying out loud: this is the reason one game can be cascading and another ways-based
        // and both get the same wild behaviours without either evaluator knowing about them.
        facts.add("Applied as a transform on the grid before it is evaluated, so it behaves identically "
                + "under paylines, ways and cascades");
        return new GameFeature("WILDS", String.join(" & ", names) + " Wilds", "✨",
                "Wilds here do more than substitute - they reshape the board before it is read.",
                facts, false);
    }

    /** Which strip sets the wild behaviours are live on, phrased the way a player reads it. */
    private static String scope(WildFeatureConfig wilds) {
        List<ReelStripSet> on = wilds.appliesToOrdered();
        if (on.size() == ReelStripSet.values().length) {
            return "Live on every spin, base game included";
        }
        if (on.equals(List.of(ReelStripSet.FREE_SPINS))) {
            return "Live in free spins only - the base game stays lean";
        }
        return "Live on: " + on.stream().map(s -> s.name().replace('_', ' ').toLowerCase(Locale.ROOT))
                .reduce((a, b) -> a + ", " + b).orElse("");
    }

    private static GameFeature freeSpins(SlotMathDefinition math) {
        List<String> facts = new ArrayList<>();
        facts.add("Triggered by " + math.scatterTriggers().minCount() + "+ scatters anywhere");
        facts.add(math.scatterTriggers().freeSpinsAwarded() + " free spins awarded");
        if (math.scatterTriggers().retriggerAwards() > 0) {
            facts.add("Retriggering inside the feature adds " + math.scatterTriggers().retriggerAwards()
                    + " more"
                    + (math.freeSpins().maxRetriggerStack() > 0
                            ? ", stacking up to " + math.freeSpins().maxRetriggerStack() : ""));
        }
        facts.add("Played on a dedicated free-spins reel strip set, not the base strips");
        if (math.freeSpins().betLockedToTriggerBet()) {
            facts.add("The stake is locked to the bet that triggered it");
        }
        return new GameFeature("FREE_SPINS", "Free Spins", "🎁",
                "The classic scatter-triggered feature, played on richer reels than the base game.",
                facts, false);
    }

    private static GameFeature pickCollect(SlotMathDefinition math) {
        return new GameFeature("PICK_COLLECT", "Pick & Collect", "🗝️",
                "A pick-until-you-bust bonus board: keep choosing tiles to bank credits and multipliers "
                        + "until the end tile turns up.",
                List.of("Triggers on roughly 1 in " + count(math.pickCollect().triggerOneInN())
                                + " base spins",
                        math.pickCollect().boardSize() + " tiles: credits, multipliers, collect and end",
                        "Capped at " + count(math.pickCollect().maxFeatureWinMultiplier())
                                + "× the stake",
                        "Every tile is drawn and settled server-side, and the board is audited per pick"),
                false);
    }

    private static GameFeature powerBet(SlotMathDefinition math) {
        return new GameFeature("POWER_BET", "Power Bet", "⚡",
                "An optional stake raise that swaps the reels underneath you for a richer set of strips.",
                List.of("Stake ×" + plain(math.powerBet().betMultiplier()),
                        "Spins draw from the dedicated POWER_BET strip set",
                        math.freeSpins().powerBetPersists()
                                ? "Stays active through free spins"
                                : "Does not carry into free spins"),
                false);
    }

    private static GameFeature bonusBuy(SlotMathDefinition math) {
        List<String> facts = new ArrayList<>();
        for (BonusBuyOption option : math.bonusBuyOptions()) {
            facts.add(buyLabel(option.buyType()) + " - " + mult(option.costMultiplier()) + " the stake");
        }
        facts.add("Each price is calibrated so a bought feature returns the game's target RTP, and the "
                + "RTP suite guards it");
        facts.add("Affordability, debit and entry all run through the same session state machine as an "
                + "organic trigger");
        return new GameFeature("BONUS_BUY", "Bonus Buy", "🛒",
                "Skip the wait and enter a feature directly for a fixed multiple of the stake.",
                facts, false);
    }

    private static String buyLabel(BonusBuyType type) {
        return switch (type) {
            case FREE_SPINS_BUY -> "Free Spins";
            case PICK_COLLECT_BUY -> "Pick & Collect";
            case HOLD_SPIN_BUY -> "Hold & Spin";
        };
    }

    // ------------------------------------------------------------------ roulette

    /**
     * @param betTypes the already-labelled bet spots from the catalog, so the payout groups below read
     *                 with the same names the client prints on the table ("1st 12", not "DOZEN_1")
     */
    static List<GameFeature> forRoulette(RouletteMathDefinition math,
                                         List<GameCatalogController.BetTypeView> betTypes) {
        int pockets = math.pocketCount();
        BigDecimal edge = BigDecimal.valueOf(100).subtract(math.targetRtp());
        return List.of(
                new GameFeature("WHEEL", "Single-Zero Wheel", "🎡",
                        "The European wheel: one green zero rather than two, which is what makes it the "
                                + "friendliest fixed-odds bet on the floor.",
                        List.of(pockets + " pockets, numbered 0-" + (pockets - 1),
                                "House edge " + edge.setScale(2, java.math.RoundingMode.HALF_UP)
                                        + "% on every bet, inside or outside",
                                "Pocket colours are derived from the wheel, not hardcoded per number"),
                        true),
                new GameFeature("BETS", "Inside & Outside Bets", "🎯",
                        "Cover a single number for the long shot, or spread the stake across the "
                                + "even-money outside for a steadier ride. Combine as many as you like "
                                + "before the spin.",
                        payoutGroups(betTypes),
                        false),
                new GameFeature("SETTLEMENT", "Server-Decided Spin", "🔒",
                        "The pocket is drawn and every covering bet settled on the server the instant "
                                + "you spin. The wheel animation only replays a result that is already "
                                + "final.",
                        List.of("Outcome drawn from the certified server-side RNG",
                                "Table limits: " + plain(math.limits().maxBetPerSpot())
                                        + " per spot, " + plain(math.limits().maxTotalBet())
                                        + " total per spin"),
                        false));
    }

    // ------------------------------------------------------------------ blackjack

    /**
     * Split deliberately along the line a player thinks in: what the house does (fixed, decides the
     * edge) versus what they may do (their decisions). The catalog's {@code blackjack.rules} list mixes
     * both because the game page prints it as one block, so the two cards are built from the math here
     * rather than by slicing that list.
     */
    static List<GameFeature> forBlackjack(BlackjackMathDefinition math, String payoutLabel) {
        List<String> house = List.of(
                math.decks() + "-deck shoe",
                "Dealer " + (math.dealerHitsSoft17() ? "hits" : "stands on") + " soft 17",
                "Blackjack pays " + payoutLabel);
        List<String> options = new ArrayList<>();
        options.add("Hit and stand");
        options.add("Double on any two cards"
                + (math.doubleAfterSplit() ? ", including after a split" : ""));
        options.add("Split a pair up to " + math.maxHands() + " hands");
        if (math.insuranceEnabled()) {
            options.add("Insurance against a dealer Ace, paying " + math.insurancePayout() + ":1");
        }
        return List.of(
                new GameFeature("TABLE_RULES", math.decks() + "-Deck "
                        + (math.dealerHitsSoft17() ? "H17" : "S17"), "🂡",
                        "The house rules, stated up front - they are what decide the edge before a "
                                + "single card is dealt.",
                        house, true),
                new GameFeature("PLAYER_OPTIONS", "Full Player Options", "♠️",
                        "Every decision the classic game allows is available, and the server tells the "
                                + "client which ones are legal on each hand rather than the other way "
                                + "round.",
                        options, false),
                new GameFeature("HOLE_CARD", "Server-Held Hole Card", "🔒",
                        "The dealer's face-down card never reaches the browser until the reveal. Round "
                                + "state is persisted between every deal and action call, so a "
                                + "refresh cannot change what was already dealt.",
                        List.of("Hole card resolved server-side on peek and reveal",
                                "Every action is idempotent - a retried request replays, it does not "
                                        + "deal again"),
                        false));
    }

    /**
     * The bet spots collapsed into one line per payout - "1:1 - Red, Black, Even, Odd, 1-18, 19-36"
     * rather than thirteen near-identical bullets. Highest payout first, which is the order a player
     * scans them in.
     */
    private static List<String> payoutGroups(List<GameCatalogController.BetTypeView> betTypes) {
        NavigableMap<Integer, List<String>> byPayout = new TreeMap<>();
        for (GameCatalogController.BetTypeView bet : betTypes) {
            byPayout.computeIfAbsent(bet.payout(), k -> new ArrayList<>()).add(bet.label());
        }
        return byPayout.descendingMap().entrySet().stream()
                .map(e -> "Pays " + e.getKey() + ":1 - " + String.join(", ", e.getValue()))
                .toList();
    }

    // ------------------------------------------------------------------ formatting

    /** "2,000×", "230.76×", "1.5×" - grouped when whole, exact when not. */
    private static String mult(BigDecimal value) {
        return plain(value) + "×";
    }

    /** Strips trailing zeros, groups thousands when the value is whole. */
    private static String plain(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.scale() <= 0) {
            return String.format(Locale.ROOT, "%,d", stripped.toBigIntegerExact());
        }
        return stripped.toPlainString();
    }

    private static String count(int value) {
        return String.format(Locale.ROOT, "%,d", value);
    }
}
