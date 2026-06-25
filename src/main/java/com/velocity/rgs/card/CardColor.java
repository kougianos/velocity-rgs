package com.velocity.rgs.card;

/**
 * The colour of a playing card, derived from its {@link Suit}. Drives nothing in the math - it exists so the
 * client can render red vs. black cards without any game logic of its own.
 */
public enum CardColor {
    RED,
    BLACK
}
