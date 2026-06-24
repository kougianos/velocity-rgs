"use strict";

/* =========================================================================
 * Velocity RGS — game-page bootstrap.
 *
 * One game page serves every game type. This resolves the ?game= id against
 * the server catalog, applies the shared chrome + info modal, then shows the
 * matching play surface and hands off to the right client module:
 *   SLOT      -> #slotGame       + initSlotGame(game)      (slot.js)
 *   ROULETTE  -> #rouletteGame   + initRouletteGame(game)  (roulette.js)
 *   BLACKJACK -> #blackjackGame  + initBlackjackGame(game) (blackjack.js)
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

  const type = game.gameType || "SLOT";
  const isSlot = type === "SLOT";
  document.getElementById("slotGame").classList.toggle("hidden", !isSlot);
  document.getElementById("rouletteGame").classList.toggle("hidden", type !== "ROULETTE");
  document.getElementById("blackjackGame").classList.toggle("hidden", type !== "BLACKJACK");
  const sim = document.getElementById("slotSimulator");
  if (sim) sim.classList.toggle("hidden", !isSlot);

  if (type === "ROULETTE") {
    window.initRouletteGame(game);
  } else if (type === "BLACKJACK") {
    window.initBlackjackGame(game);
  } else {
    window.initSlotGame(game);
  }
}

document.addEventListener("DOMContentLoaded", bootGamePage);
