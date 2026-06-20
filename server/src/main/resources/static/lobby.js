"use strict";

/* =========================================================================
 * Velocity RGS — lobby. Fetches the live game catalog from the backend and
 * renders a themed card per game. Clicking a card opens the game page.
 * ======================================================================= */

const fmtInt = (n) => Number(n ?? 0).toLocaleString();

async function loadGames() {
  const grid = document.getElementById("gameGrid");
  try {
    const res = await fetch("/api/v1/games");
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const games = await res.json();
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
  const grid = document.getElementById("gameGrid");
  grid.innerHTML = "";
  for (const g of games) {
    const meta = gameMeta(g.gameId);
    const rtp = Number(g.targetRtp).toFixed(1);
    const vol = meta.volatility;

    const card = document.createElement("a");
    card.className = "game-card";
    card.dataset.theme = meta.theme;
    card.href = `game.html?game=${encodeURIComponent(g.gameId)}`;
    card.innerHTML = `
      <div class="card-art">
        <span class="card-logo">${meta.logo}</span>
        <span class="vol-badge vol-${vol.toLowerCase()}">${vol} volatility</span>
      </div>
      <div class="card-body">
        <h3>${meta.name}</h3>
        <p class="card-tagline">${meta.tagline}</p>
        <div class="card-stats">
          <div class="stat"><span class="stat-label">RTP</span><span class="stat-value">${rtp}%</span></div>
          <div class="stat"><span class="stat-label">Max win</span><span class="stat-value">${fmtInt(g.maxWinMultiplier)}×</span></div>
          <div class="stat"><span class="stat-label">Free spins</span><span class="stat-value">${g.freeSpinsAwarded}</span></div>
        </div>
        <span class="play-btn">Play ▶</span>
      </div>`;
    grid.appendChild(card);
  }
}

document.addEventListener("DOMContentLoaded", loadGames);
