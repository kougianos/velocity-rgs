package com.velocity.rgs.catalog;

/**
 * The category a catalog game belongs to. Drives how the lobby groups cards and which client renderer the
 * game page loads — slots draw reels/paylines, roulette draws a wheel + betting table. Surfaced on every
 * {@code GET /api/v1/games} entry so the browser client routes without hardcoding game ids.
 */
public enum GameType {
    SLOT,
    ROULETTE
}
