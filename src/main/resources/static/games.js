"use strict";

/* =========================================================================
 * Velocity RGS - game catalog client.
 *
 * The backend GET /api/v1/games endpoint is the single source of truth for
 * everything about a game: presentation (title, tagline, theme, logo, per-symbol
 * glyphs), the grid shape + paylines used to draw and highlight the reels, and the
 * headline math facts (RTP, max-win, buy costs). Nothing about a game is hardcoded
 * here - this module just fetches that catalog once, caches it, and exposes small
 * helpers to shape it for rendering. Loaded by both the lobby (index.html) and the
 * game page (game.html).
 * ======================================================================= */

let _catalogPromise = null;

/** Fetch the full game catalog from the backend, memoized so repeat callers share one request. */
function fetchCatalog() {
  if (!_catalogPromise) {
    _catalogPromise = fetch("/api/v1/games")
      .then((res) => {
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        return res.json();
      })
      .catch((err) => {
        // Reset so a later caller can retry after a transient failure.
        _catalogPromise = null;
        throw err;
      });
  }
  return _catalogPromise;
}

/** Resolve a game from the catalog by id, falling back to the first registered game. */
function resolveGame(catalog, gameId) {
  if (!Array.isArray(catalog) || catalog.length === 0) return null;
  return catalog.find((g) => g.gameId === gameId) || catalog[0];
}

/** Build a { symbolId: { glyph, name } } lookup from a game's symbol list. */
function buildSymbolMap(game) {
  const map = {};
  for (const s of game.symbols || []) map[s.id] = { glyph: s.glyph, name: s.name };
  return map;
}

/** Build a { lineId: coords } lookup (coords = ordered [row, col] pairs) from a game's paylines. */
function buildPaylineMap(game) {
  const map = {};
  for (const p of game.paylines || []) map[p.id] = p.coords;
  return map;
}
