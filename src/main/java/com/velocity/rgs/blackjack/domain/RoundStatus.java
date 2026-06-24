package com.velocity.rgs.blackjack.domain;

/**
 * Lifecycle of a blackjack round. A round is {@link #IN_PROGRESS} from the deal until every player hand is
 * resolved and the dealer has played; it then becomes {@link #SETTLED}. The active round for a session is the
 * single row with {@code status = IN_PROGRESS}.
 */
public enum RoundStatus {
    IN_PROGRESS,
    SETTLED
}
