package com.velocity.rgs.blackjack.config;

import com.velocity.rgs.catalog.BetConfig;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Root math model for a blackjack game — the {@code math} block of {@code games/<gameId>/<mathVersion>.json}.
 * Immutable; the canonical constructor enforces structural invariants so malformed JSON fails fast at startup
 * (mirrors {@code RouletteMathDefinition}).
 *
 * <p>Unlike slots/roulette there is <b>no single exact RTP</b> — blackjack's return depends on player
 * decisions — so {@code targetRtp} is the basic-strategy figure for display only and is never asserted
 * tightly. {@code blackjackPayout} and {@code insurancePayout} are "to-one" ratios (3:2 → {@code 1.5},
 * 2:1 → {@code 2}). {@code maxSplits} is the number of re-splits allowed, so total hands ≤ {@code maxSplits + 1}.
 */
public record BlackjackMathDefinition(
        String gameId,
        String mathVersion,
        String variant,
        BigDecimal targetRtp,
        int decks,
        boolean dealerHitsSoft17,
        BigDecimal blackjackPayout,
        boolean doubleAfterSplit,
        int maxSplits,
        boolean insuranceEnabled,
        int insurancePayout,
        BetConfig betConfig,
        BlackjackLimits limits
) {

    public BlackjackMathDefinition {
        Objects.requireNonNull(gameId, "gameId");
        Objects.requireNonNull(mathVersion, "mathVersion");
        Objects.requireNonNull(variant, "variant");
        Objects.requireNonNull(targetRtp, "targetRtp");
        Objects.requireNonNull(blackjackPayout, "blackjackPayout");
        Objects.requireNonNull(betConfig, "betConfig");
        Objects.requireNonNull(limits, "limits");

        if (gameId.isBlank() || mathVersion.isBlank() || variant.isBlank()) {
            throw new IllegalArgumentException("gameId/mathVersion/variant must not be blank");
        }
        if (targetRtp.signum() <= 0 || targetRtp.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("targetRtp must be a percentage in (0, 100], found " + targetRtp);
        }
        if (decks < 1) {
            throw new IllegalArgumentException("decks must be >= 1, found " + decks);
        }
        if (blackjackPayout.signum() <= 0) {
            throw new IllegalArgumentException("blackjackPayout must be positive, found " + blackjackPayout);
        }
        if (maxSplits < 0) {
            throw new IllegalArgumentException("maxSplits must be >= 0, found " + maxSplits);
        }
        if (insurancePayout < 0) {
            throw new IllegalArgumentException("insurancePayout must be >= 0, found " + insurancePayout);
        }
    }

    /** Maximum number of hands a player may hold after splitting/re-splitting. */
    public int maxHands() {
        return maxSplits + 1;
    }
}
