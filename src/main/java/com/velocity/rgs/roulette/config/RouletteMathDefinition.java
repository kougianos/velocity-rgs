package com.velocity.rgs.roulette.config;

import com.velocity.rgs.catalog.BetConfig;
import com.velocity.rgs.roulette.domain.RouletteBetKind;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Root math model for a roulette game - the {@code math} block of {@code games/<gameId>/<mathVersion>.json}.
 * Immutable; collections are defensively copied and the canonical constructor enforces structural invariants
 * so malformed JSON fails fast at startup. A European single-zero wheel is {@code pocketCount = 37}
 * (numbers 0–36); {@code redNumbers} fixes the wheel colours, {@code betTypes} the pay schedule, and the
 * shared {@link BetConfig} the chip denominations. There is no RTP calibration to do: with standard payouts
 * every bet returns {@code (covered/pockets) × (payout + 1) = 36/37 = 97.30%}.
 */
public record RouletteMathDefinition(
        String gameId,
        String mathVersion,
        String variant,
        BigDecimal targetRtp,
        int pocketCount,
        Set<Integer> redNumbers,
        List<RouletteBetTypeConfig> betTypes,
        BetConfig betConfig,
        RouletteLimits limits
) {

    public RouletteMathDefinition {
        Objects.requireNonNull(gameId, "gameId");
        Objects.requireNonNull(mathVersion, "mathVersion");
        Objects.requireNonNull(variant, "variant");
        Objects.requireNonNull(targetRtp, "targetRtp");
        Objects.requireNonNull(redNumbers, "redNumbers");
        Objects.requireNonNull(betTypes, "betTypes");
        Objects.requireNonNull(betConfig, "betConfig");
        Objects.requireNonNull(limits, "limits");

        if (gameId.isBlank() || mathVersion.isBlank() || variant.isBlank()) {
            throw new IllegalArgumentException("gameId/mathVersion/variant must not be blank");
        }
        if (targetRtp.signum() <= 0 || targetRtp.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("targetRtp must be a percentage in (0, 100], found " + targetRtp);
        }
        if (pocketCount < 2) {
            throw new IllegalArgumentException("pocketCount must be >= 2, found " + pocketCount);
        }
        Set<Integer> reds = new LinkedHashSet<>();
        for (Integer n : redNumbers) {
            if (n == null || n < 1 || n >= pocketCount) {
                throw new IllegalArgumentException(
                        "redNumbers entry " + n + " out of range [1, " + (pocketCount - 1) + "]");
            }
            if (!reds.add(n)) {
                throw new IllegalArgumentException("redNumbers has a duplicate: " + n);
            }
        }
        if (reds.isEmpty()) {
            throw new IllegalArgumentException("redNumbers must not be empty");
        }
        if (betTypes.isEmpty()) {
            throw new IllegalArgumentException("betTypes must not be empty");
        }
        Set<RouletteBetKind> seen = new LinkedHashSet<>();
        for (RouletteBetTypeConfig bt : betTypes) {
            if (!seen.add(bt.kind())) {
                throw new IllegalArgumentException("duplicate betType kind: " + bt.kind());
            }
        }
        redNumbers = Set.copyOf(reds);
        betTypes = List.copyOf(betTypes);
    }

    /** The configured bet type for {@code kind}, if this game offers it. */
    public Optional<RouletteBetTypeConfig> betType(RouletteBetKind kind) {
        return betTypes.stream().filter(bt -> bt.kind() == kind).findFirst();
    }

    /** Highest number on the wheel (pocketCount - 1) - 36 for a European single-zero wheel. */
    public int highestNumber() {
        return pocketCount - 1;
    }
}
