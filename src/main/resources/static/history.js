"use strict";

/* =========================================================================
 * Velocity RGS - Round History page.
 *
 * Reads the demo player id persisted by the game page (localStorage), mints a
 * short-lived ADMIN dev token for it, and renders that player's persisted rounds
 * from GET /api/v1/admin/rounds/{playerId}. Game titles/logos come from the same
 * server catalog the lobby and game pages use (games.js).
 * ======================================================================= */

const CURRENCY = "EUR";
const PLAYER_KEY = "velocity.playerId";

const $ = (id) => document.getElementById(id);
const els = {
  player: $("historyPlayer"),
  summary: $("historySummary"),
  body: $("historyBody"),
  refresh: $("refreshHistory"),
  toast: $("toast"),
};

let toastTimer = null;
function toast(message, kind = "") {
  els.toast.textContent = message;
  els.toast.className = "toast " + kind;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => els.toast.classList.add("hidden"), 3200);
}

function fmt(value) {
  const n = Number(value ?? 0);
  return n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function fmtTime(iso) {
  if (!iso) return "-";
  const d = new Date(iso);
  if (isNaN(d)) return iso;
  return d.toLocaleString();
}

/** Human-friendly label for the persisted state context (BASE_GAME, FREE_SPINS_LOOP, …). */
function stateLabel(state) {
  if (!state) return "-";
  return state.replace(/_/g, " ").toLowerCase().replace(/\b\w/g, (c) => c.toUpperCase());
}

/** Mint a short-lived ADMIN token for the given player so the admin round endpoint accepts us. */
async function mintToken(playerId) {
  const res = await fetch("/api/v1/dev/token", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      playerId,
      sessionId: crypto.randomUUID(),
      currency: CURRENCY,
      roles: ["PLAYER", "ADMIN"],
      ttlMinutes: 60,
    }),
  });
  if (!res.ok) throw new Error(`Token mint failed (HTTP ${res.status})`);
  return (await res.json()).token;
}

async function fetchRounds(playerId, token) {
  const res = await fetch(`/api/v1/admin/rounds/${encodeURIComponent(playerId)}`, {
    headers: { Authorization: "Bearer " + token },
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}

/** Map gameId -> { title, logo } from the catalog so rows show the game name, not the raw id. */
async function loadGameLookup() {
  try {
    const catalog = await fetchCatalog();
    const map = {};
    for (const g of catalog || []) map[g.gameId] = { title: g.title, logo: g.logo };
    return map;
  } catch {
    return {};
  }
}

function renderSummary(rounds) {
  const total = rounds.length;
  const totalBet = rounds.reduce((s, r) => s + Number(r.betAmount || 0), 0);
  const totalWin = rounds.reduce((s, r) => s + Number(r.totalWin || 0), 0);
  const net = totalWin - totalBet;
  const wins = rounds.filter((r) => Number(r.totalWin || 0) > 0).length;
  const hitRate = total ? (wins / total) * 100 : 0;

  const card = (label, value, cls = "") =>
    `<div class="stat-card"><span class="stat-card-label">${label}</span>` +
    `<span class="stat-card-value ${cls}">${value}</span></div>`;

  els.summary.innerHTML =
    card("Rounds", total.toLocaleString()) +
    card("Total Bet", `${fmt(totalBet)} ${CURRENCY}`) +
    card("Total Win", `${fmt(totalWin)} ${CURRENCY}`) +
    card("Net", `${net >= 0 ? "+" : "−"}${fmt(Math.abs(net))} ${CURRENCY}`, net >= 0 ? "pos" : "neg") +
    card("Hit Rate", `${fmt(hitRate)}%`);
}

function renderTable(rounds, games) {
  if (!rounds.length) {
    els.body.innerHTML =
      `<p class="history-empty">No rounds recorded yet for this player. ` +
      `<a href="/">Pick a game</a> and spin to build your history.</p>`;
    return;
  }

  const rows = rounds.map((r) => {
    const win = Number(r.totalWin || 0);
    const bet = Number(r.betAmount || 0);
    const net = win - bet;
    const outcome = win > 0
      ? `<span class="badge badge-win">Win</span>`
      : `<span class="badge badge-loss">Loss</span>`;
    const game = games[r.gameId] || { title: r.gameId, logo: "🎰" };
    const power = r.powerBetActive ? `<span class="badge badge-power">Power</span>` : "";
    return `
      <tr class="${win > 0 ? "row-win" : "row-loss"}">
        <td class="col-time">${fmtTime(r.createdAt)}</td>
        <td><span class="game-logo">${game.logo || "🎰"}</span> ${game.title || r.gameId}</td>
        <td>${stateLabel(r.stateContext)} ${power}</td>
        <td class="num">${fmt(bet)}</td>
        <td class="num win-cell">${fmt(win)}</td>
        <td class="num ${net >= 0 ? "pos" : "neg"}">${net >= 0 ? "+" : "−"}${fmt(Math.abs(net))}</td>
        <td>${outcome}</td>
        <td class="col-round" title="${r.roundId}">${r.roundId}</td>
      </tr>`;
  }).join("");

  els.body.innerHTML = `
    <table class="history-table">
      <thead>
        <tr>
          <th>Time</th><th>Game</th><th>Context</th>
          <th class="num">Bet</th><th class="num">Win</th><th class="num">Net</th>
          <th>Outcome</th><th>Round Id</th>
        </tr>
      </thead>
      <tbody>${rows}</tbody>
    </table>`;
}

async function load() {
  const playerId = localStorage.getItem(PLAYER_KEY);
  els.player.textContent = playerId || "-";

  if (!playerId) {
    els.body.innerHTML =
      `<p class="history-empty">No demo player yet. ` +
      `<a href="/">Open a game</a> and spin once - your rounds will appear here.</p>`;
    els.summary.innerHTML = "";
    return;
  }

  els.body.innerHTML = `<p class="history-loading">Loading round history…</p>`;
  try {
    const [games, token] = await Promise.all([loadGameLookup(), mintToken(playerId)]);
    const rounds = await fetchRounds(playerId, token);
    renderSummary(rounds);
    renderTable(rounds, games);
  } catch (e) {
    els.summary.innerHTML = "";
    els.body.innerHTML = `<p class="history-error">Could not load history: ${e.message}</p>`;
    toast(`History failed: ${e.message}`, "error");
  }
}

els.refresh.addEventListener("click", load);
document.addEventListener("DOMContentLoaded", load);
