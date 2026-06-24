package com.velocity.rgs.blackjack.domain;

/**
 * The settled result of a single player hand versus the dealer. {@link #PLAYER_BLACKJACK} is paid at the
 * blackjack rate (3:2); {@link #WIN} pays even money; {@link #PUSH} returns the stake; {@link #LOSE} forfeits
 * it.
 */
public enum BlackjackOutcome {
    PLAYER_BLACKJACK,
    WIN,
    PUSH,
    LOSE
}
