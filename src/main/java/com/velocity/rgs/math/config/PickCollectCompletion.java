package com.velocity.rgs.math.config;

/**
 * Pick & Collect completion rule per A.4 / Section 5.
 * <ul>
 *   <li>{@code FIXED_PICKS} — feature ends after {@code value} picks</li>
 *   <li>{@code END_TILE} — feature ends when an END tile is revealed; {@code value} is ignored</li>
 *   <li>{@code COLLECT_THRESHOLD} — feature ends when accumulated collect reaches {@code value} (bet multiplier)</li>
 * </ul>
 */
public record PickCollectCompletion(CompletionType type, int value) {

    public PickCollectCompletion {
        if (type == null) {
            throw new IllegalArgumentException("pickCollect.completion.type required");
        }
        if (type != CompletionType.END_TILE && value <= 0) {
            throw new IllegalArgumentException(
                    "pickCollect.completion.value must be > 0 for type " + type);
        }
    }

    public enum CompletionType {
        FIXED_PICKS,
        END_TILE,
        COLLECT_THRESHOLD
    }
}
