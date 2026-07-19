package com.velocity.rgs.slot.domain;

/**
 * What kind of round a {@code game_round} row records - which decides how it is reconstructed.
 *
 * <p>The distinction is not cosmetic. A {@link #SPIN} is self-contained: its recorded draws are a whole
 * reel spin, so replaying them through {@code GridGenerationEngine.generate} rebuilds the board from
 * nothing. A {@link #RESPIN} is not: it re-draws only the cells that were not already holding a coin,
 * so its draws only mean anything alongside the coins held going in. Replaying one as the other fails
 * on the first draw, because the two do not even agree on how many draws there should be.
 */
public enum RoundKind {

    /** A base, free or bonus-bought spin: the whole grid was drawn. */
    SPIN,

    /**
     * One Hold &amp; Spin respin: only the unlocked cells were re-drawn. Reconstructing it needs
     * {@code game_round.feature_context}, which carries the coins held before the respin.
     */
    RESPIN
}
