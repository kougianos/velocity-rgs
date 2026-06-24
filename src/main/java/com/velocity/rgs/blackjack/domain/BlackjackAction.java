package com.velocity.rgs.blackjack.domain;

/**
 * The actions a player can take in a blackjack round. {@link #DEAL} starts a fresh round; the rest act on the
 * active hand of an in-progress round. Lives in the blackjack package so the shared
 * {@code session.domain.GameCommand} (slot/roulette-oriented) is not polluted. The set of <i>available</i>
 * actions at any moment is derived server-side from the hand state, never trusted from the client.
 */
public enum BlackjackAction {
    DEAL,
    HIT,
    STAND,
    DOUBLE,
    SPLIT,
    INSURANCE
}
