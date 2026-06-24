package com.velocity.rgs.blackjack.domain;

/**
 * The play state of a single player hand within a round. {@link #ACTIVE} is the only non-terminal state — the
 * dealer plays and the round settles once every hand is terminal. {@link #BLACKJACK} is reserved for a
 * <i>natural</i> (the original two-card 21); a 21 reached by drawing, or any 21 on a post-split hand, is a
 * normal {@link #STAND} and pays even money, not 3:2.
 */
public enum HandStatus {
    ACTIVE,
    STAND,
    BUST,
    BLACKJACK,
    DOUBLED;

    public boolean isTerminal() {
        return this != ACTIVE;
    }
}
