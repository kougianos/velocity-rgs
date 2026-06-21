"use strict";

/* =========================================================================
 * Velocity RGS — shared game presentation metadata.
 *
 * The backend GET /api/v1/games endpoint is the source of truth for WHICH
 * games exist and their math facts (RTP, max-win multiplier, buy costs). This
 * file only layers on presentation: theme, glyphs and copy keyed by gameId.
 * Loaded by both the lobby (index.html) and the game page (game.html).
 * ======================================================================= */

const GAME_META = {
  "aztec-fire": {
    name: "Aztec Fire",
    tagline: "Sun-scorched reels and blazing free spins.",
    logo: "🔥",
    theme: "fire",
    volatility: "Medium",
    symbols: {
      1:  { glyph: "A",  name: "Ace"    },
      2:  { glyph: "K",  name: "King"   },
      3:  { glyph: "Q",  name: "Queen"  },
      4:  { glyph: "J",  name: "Jack"   },
      5:  { glyph: "10", name: "Ten"    },
      6:  { glyph: "9",  name: "Nine"   },
      7:  { glyph: "🗿", name: "Statue" },
      8:  { glyph: "👹", name: "Mask"   },
      9:  { glyph: "⭐", name: "Wild"   },
      12: { glyph: "💎", name: "Scatter"},
    },
  },
  "frost-crown": {
    name: "Frost Crown",
    tagline: "Steady, frequent wins beneath the northern lights.",
    logo: "❄️",
    theme: "frost",
    volatility: "Low",
    symbols: {
      1:  { glyph: "A",  name: "Ace"    },
      2:  { glyph: "K",  name: "King"   },
      3:  { glyph: "Q",  name: "Queen"  },
      4:  { glyph: "J",  name: "Jack"   },
      5:  { glyph: "10", name: "Ten"    },
      6:  { glyph: "9",  name: "Nine"   },
      7:  { glyph: "🦉", name: "Owl"    },
      8:  { glyph: "👑", name: "Crown"  },
      9:  { glyph: "❄️", name: "Wild"   },
      12: { glyph: "💠", name: "Scatter"},
    },
  },
  "inferno-riches": {
    name: "Inferno Riches",
    tagline: "Brave the volcano for monstrous 25,000× hits.",
    logo: "🌋",
    theme: "inferno",
    volatility: "High",
    symbols: {
      1:  { glyph: "A",  name: "Ace"     },
      2:  { glyph: "K",  name: "King"    },
      3:  { glyph: "Q",  name: "Queen"   },
      4:  { glyph: "J",  name: "Jack"    },
      5:  { glyph: "10", name: "Ten"     },
      6:  { glyph: "9",  name: "Nine"    },
      7:  { glyph: "🦅", name: "Phoenix" },
      8:  { glyph: "🐉", name: "Dragon"  },
      9:  { glyph: "🔥", name: "Wild"    },
      12: { glyph: "💰", name: "Scatter" },
    },
  },
};

const DEFAULT_GAME_ID = "aztec-fire";

/** Resolve presentation metadata for a gameId, falling back to the default game. */
function gameMeta(gameId) {
  return GAME_META[gameId] || GAME_META[DEFAULT_GAME_ID];
}
