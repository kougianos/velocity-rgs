"use strict";

/* =========================================================================
 * Velocity RGS — game-page bootstrap.
 *
 * One game page serves every game type. This resolves the ?game= id against
 * the server catalog, applies the shared chrome + info modal, then shows the
 * matching play surface and hands off to the right client module:
 *   SLOT     -> #slotGame      + initSlotGame(game)     (slot.js)
 *   ROULETTE -> #rouletteGame  + initRouletteGame(game) (roulette.js)
 * The RTP simulator is slot-only and hidden for other game types.
 * ======================================================================= */

async function bootGamePage() {
  let catalog;
  try {
    catalog = await fetchCatalog();
  } catch (e) {
    toast(`Could not load game config: ${e.message}`, "error");
    return;
  }

  const gameId = new URLSearchParams(location.search).get("game") || "";
  const game = resolveGame(catalog, gameId);
  if (!game) {
    toast("No games are registered on the server", "error");
    return;
  }

  applyGameChrome(game);
  initInfoModal(game);

  const isRoulette = game.gameType === "ROULETTE";
  document.getElementById("slotGame").classList.toggle("hidden", isRoulette);
  document.getElementById("rouletteGame").classList.toggle("hidden", !isRoulette);
  const sim = document.getElementById("slotSimulator");
  if (sim) sim.classList.toggle("hidden", isRoulette);

  if (isRoulette) {
    window.initRouletteGame(game);
  } else {
    window.initSlotGame(game);
  }
}

document.addEventListener("DOMContentLoaded", bootGamePage);
