package com.velocity.rgs.slot.math.config;

import com.velocity.rgs.catalog.BetConfig;
import com.velocity.rgs.slot.math.domain.Payline;
import com.velocity.rgs.slot.math.domain.PayTable;
import com.velocity.rgs.slot.math.domain.ReelStrip;
import com.velocity.rgs.slot.math.domain.ReelStripSet;
import com.velocity.rgs.slot.math.domain.Symbol;
import com.velocity.rgs.slot.math.domain.SymbolType;
import com.velocity.rgs.slot.math.domain.WinModel;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Root math model - the {@code math} block of {@code games/<gameId>/<mathVersion>.json} per A.4. Immutable; all collections
 * are defensively copied. The canonical constructor enforces structural invariants so any malformed JSON
 * fails fast at startup.
 */
public record SlotMathDefinition(
        String gameId,
        String mathVersion,
        BigDecimal targetRtp,
        Grid grid,
        WinModel winModel,
        List<Symbol> symbols,
        List<Payline> paylines,
        PayTable payTable,
        Map<ReelStripSet, List<ReelStrip>> reelStrips,
        ScatterTriggers scatterTriggers,
        FreeSpinsConfig freeSpins,
        PowerBetConfig powerBet,
        List<BonusBuyOption> bonusBuyOptions,
        PickCollectConfig pickCollect,
        Limits limits,
        BetConfig betConfig
) {

    public SlotMathDefinition {
        Objects.requireNonNull(gameId, "gameId");
        Objects.requireNonNull(mathVersion, "mathVersion");
        Objects.requireNonNull(targetRtp, "targetRtp");
        Objects.requireNonNull(grid, "grid");
        Objects.requireNonNull(symbols, "symbols");
        Objects.requireNonNull(paylines, "paylines");
        Objects.requireNonNull(payTable, "payTable");
        Objects.requireNonNull(reelStrips, "reelStrips");
        Objects.requireNonNull(scatterTriggers, "scatterTriggers");
        Objects.requireNonNull(freeSpins, "freeSpins");
        Objects.requireNonNull(powerBet, "powerBet");
        Objects.requireNonNull(bonusBuyOptions, "bonusBuyOptions");
        Objects.requireNonNull(pickCollect, "pickCollect");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(betConfig, "betConfig");

        if (gameId.isBlank() || mathVersion.isBlank()) {
            throw new IllegalArgumentException("gameId/mathVersion must not be blank");
        }
        if (targetRtp.signum() <= 0 || targetRtp.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("targetRtp must be a percentage in (0, 100], found " + targetRtp);
        }
        if (symbols.isEmpty()) {
            throw new IllegalArgumentException("symbols cannot be empty");
        }

        // Absent in every game authored before ways-to-win existed, and those are all payline games.
        if (winModel == null) {
            winModel = WinModel.PAYLINES;
        }
        // Each model owns the paylines list outright: PAYLINES needs it, WAYS derives paths from the
        // grid and must not carry one. Rejecting the overlap keeps config from claiming two things at
        // once - a ways game with leftover paylines would silently look like it had 20 active lines.
        if (winModel == WinModel.PAYLINES && paylines.isEmpty()) {
            throw new IllegalArgumentException("winModel=PAYLINES requires a non-empty paylines list");
        }
        if (winModel == WinModel.WAYS && !paylines.isEmpty()) {
            throw new IllegalArgumentException(
                    "winModel=WAYS must not declare paylines; ways paths are implied by the grid, found "
                            + paylines.size());
        }

        Set<Integer> symbolIds = new HashSet<>();
        long wildCount = 0;
        long scatterCount = 0;
        Integer wildId = null;
        for (Symbol s : symbols) {
            if (!symbolIds.add(s.id())) {
                throw new IllegalArgumentException("duplicate symbol id: " + s.id());
            }
            if (s.type() == SymbolType.WILD) {
                wildCount++;
                wildId = s.id();
            } else if (s.type() == SymbolType.SCATTER) {
                scatterCount++;
            }
        }
        if (wildCount != 1) {
            throw new IllegalArgumentException("exactly one WILD symbol is required, found " + wildCount);
        }
        if (scatterCount != 1) {
            throw new IllegalArgumentException("exactly one SCATTER symbol is required, found " + scatterCount);
        }

        // Under WAYS a wild belongs to every standard symbol's run at once, so "wild substitutes for
        // everything" and "wild pays as itself" cannot both hold without deciding what a path made
        // entirely of wilds is worth - it would otherwise pay once per substituted symbol AND once as
        // wild. Ways games here settle that by making wilds substitute-only. The entry would be dead
        // config anyway once the reel-0 rule below applies, so reject it rather than silently ignore it.
        if (winModel == WinModel.WAYS && payTable.coefficients().containsKey(wildId)) {
            throw new IllegalArgumentException(
                    "winModel=WAYS must not give the WILD symbol (id " + wildId + ") its own pay table entries; "
                            + "wilds substitute only under the ways model");
        }

        Set<Integer> paylineIds = new HashSet<>();
        for (Payline p : paylines) {
            if (!paylineIds.add(p.id())) {
                throw new IllegalArgumentException("duplicate payline id: " + p.id());
            }
            if (p.coords().length != grid.cols()) {
                throw new IllegalArgumentException(
                        "payline " + p.id() + " has " + p.coords().length + " coords; expected " + grid.cols());
            }
            for (int[] c : p.coords()) {
                if (c[0] >= grid.rows() || c[1] >= grid.cols()) {
                    throw new IllegalArgumentException(
                            "payline " + p.id() + " coord [" + c[0] + "," + c[1] + "] out of grid bounds");
                }
            }
        }

        for (ReelStripSet required : ReelStripSet.values()) {
            List<ReelStrip> strips = reelStrips.get(required);
            if (strips == null || strips.size() != grid.cols()) {
                throw new IllegalArgumentException(
                        "reelStrips." + required + " must define exactly " + grid.cols() + " strips");
            }
            for (ReelStrip strip : strips) {
                for (int sym : strip.symbols()) {
                    if (!symbolIds.contains(sym)) {
                        throw new IllegalArgumentException(
                                "reelStrips." + required + " references unknown symbol id " + sym);
                    }
                }
            }

            // Ways runs start at reel 0, so a wild there could anchor a run of pure wilds - the one case
            // where substitution overlaps itself, paying a single path once per symbol it stands in for.
            // Keeping wilds off the leftmost reel makes that structurally impossible rather than merely
            // disallowed, and bounds how many symbols can win at once to whatever reel 0 actually shows.
            // It is also the common convention in real ways games. PAYLINES is unaffected: a line pays
            // once, for the better of its wild run and its substituted run.
            if (winModel == WinModel.WAYS) {
                for (int sym : strips.get(0).symbols()) {
                    if (sym == wildId) {
                        throw new IllegalArgumentException(
                                "winModel=WAYS must not place the WILD symbol (id " + wildId + ") on reel 0 of "
                                        + "reelStrips." + required + "; a ways run must be anchored by a real "
                                        + "symbol on the leftmost reel");
                    }
                }
            }
        }

        symbols = List.copyOf(symbols);
        paylines = List.copyOf(paylines);
        bonusBuyOptions = List.copyOf(bonusBuyOptions);
        reelStrips = Map.copyOf(reelStrips);
    }
}
