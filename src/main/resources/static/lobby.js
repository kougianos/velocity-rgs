"use strict";

/* =========================================================================
 * Velocity RGS — lobby. Fetches the live game catalog from the backend (see
 * games.js) and renders themed cards grouped by game type: a row of slot games
 * and a row of roulette. Every card detail — theme, logo, title, copy and the
 * headline math facts — comes straight from the server config. Clicking a card
 * opens the (single, type-aware) game page.
 * ======================================================================= */

const fmtInt = (n) => Number(n ?? 0).toLocaleString();
const titleCase = (s) => (s || "").charAt(0).toUpperCase() + (s || "").slice(1).toLowerCase();

async function loadGames() {
  const grid = document.getElementById("gameGrid");
  try {
    const games = await fetchCatalog();
    if (!Array.isArray(games) || games.length === 0) {
      grid.innerHTML = `<p class="lobby-error">No games are registered on the server.</p>`;
      return;
    }
    renderGames(games);
  } catch (e) {
    grid.innerHTML = `<p class="lobby-error">Could not load games: ${e.message}</p>`;
  }
}

function renderGames(games) {
  const root = document.getElementById("gameGrid");
  root.innerHTML = "";
  const slots = games.filter((g) => g.gameType !== "ROULETTE");
  const tables = games.filter((g) => g.gameType === "ROULETTE");

  if (slots.length) root.appendChild(section("Slot Games", slots, false));
  if (tables.length) root.appendChild(section("Roulette", tables, true));
}

/** A titled row of game cards. `centered` lays a single card (roulette) in the middle of the row. */
function section(title, games, centered) {
  const wrap = document.createElement("section");
  wrap.className = "lobby-section";

  const heading = document.createElement("h3");
  heading.className = "lobby-section-title";
  heading.textContent = title;
  wrap.appendChild(heading);

  const grid = document.createElement("div");
  grid.className = "game-grid" + (centered ? " centered" : "");
  for (const g of games) grid.appendChild(renderCard(g));
  wrap.appendChild(grid);
  return wrap;
}

function renderCard(g) {
  const rtp = Number(g.targetRtp).toFixed(g.gameType === "ROULETTE" ? 2 : 1);
  const vol = g.volatility || "";

  const card = document.createElement("a");
  card.className = "game-card";
  card.dataset.theme = g.theme;
  card.href = `game.html?game=${encodeURIComponent(g.gameId)}`;

  const stats = g.gameType === "ROULETTE" ? rouletteStats(g, rtp) : slotStats(g, rtp);
  card.innerHTML = `
    <div class="card-art">
      <span class="card-logo">${g.logo}</span>
      <span class="vol-badge vol-${vol.toLowerCase()}">${vol} volatility</span>
    </div>
    <div class="card-body">
      <h3>${g.title}</h3>
      <p class="card-tagline">${g.tagline}</p>
      <div class="card-stats">${stats}</div>
      <span class="play-btn">Play ▶</span>
    </div>`;
  return card;
}

function slotStats(g, rtp) {
  return (
    stat("RTP", `${rtp}%`) +
    stat("Max win", `${fmtInt(g.maxWinMultiplier)}×`) +
    stat("Free spins", g.freeSpinsAwarded)
  );
}

function rouletteStats(g, rtp) {
  const r = g.roulette || {};
  return (
    stat("RTP", `${rtp}%`) +
    stat("Variant", titleCase(r.variant)) +
    stat("Max win", `${fmtInt(r.maxPayoutMultiplier)}×`)
  );
}

function stat(label, value) {
  return `<div class="stat"><span class="stat-label">${label}</span><span class="stat-value">${value}</span></div>`;
}

document.addEventListener("DOMContentLoaded", loadGames);
