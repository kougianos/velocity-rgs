package com.velocity.rgs.slot.math.config;

import com.velocity.rgs.catalog.BetConfig;
import com.velocity.rgs.slot.math.domain.Payline;
import com.velocity.rgs.slot.math.domain.PayTable;
import com.velocity.rgs.slot.math.domain.ReelStrip;
import com.velocity.rgs.slot.math.domain.ReelStripSet;
import com.velocity.rgs.slot.math.domain.Symbol;
import com.velocity.rgs.slot.math.domain.SymbolType;

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
        if (paylines.isEmpty()) {
            throw new IllegalArgumentException("paylines cannot be empty");
        }

        Set<Integer> symbolIds = new HashSet<>();
        long wildCount = 0;
        long scatterCount = 0;
        for (Symbol s : symbols) {
            if (!symbolIds.add(s.id())) {
                throw new IllegalArgumentException("duplicate symbol id: " + s.id());
            }
            if (s.type() == SymbolType.WILD) {
                wildCount++;
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
        }

        symbols = List.copyOf(symbols);
        paylines = List.copyOf(paylines);
        bonusBuyOptions = List.copyOf(bonusBuyOptions);
        reelStrips = Map.copyOf(reelStrips);
    }
}
