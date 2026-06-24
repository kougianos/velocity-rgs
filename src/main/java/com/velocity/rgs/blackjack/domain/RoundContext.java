package com.velocity.rgs.blackjack.domain;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Round-level settlement context persisted in the {@code outcomes} JSONB column: whether insurance is still on
 * offer (dealer Ace, not yet decided), which hand the player is acting on, whether the dealer's hole card has
 * been revealed (round over), and the insurance side bet if one was placed. Per-hand outcomes/payouts live on
 * the hands themselves ({@link HandState}); this captures everything else needed to resume or render a round.
 */
@Getter
@Setter
@NoArgsConstructor
public class RoundContext {

    private boolean insuranceOffered;
    private int activeHandIndex = -1;
    private boolean dealerRevealed;
    private InsuranceState insurance;

    /**
     * Monotonic counter for additional wagers within the round (double/split). Yields short, deterministic
     * wallet transaction ids ({@code <roundId>:double:<n>}) that fit the 64-char column and are idempotent on
     * retry — unlike a random UUID, which both overflowed the column and risked double-charging.
     */
    private int betSeq;
}
