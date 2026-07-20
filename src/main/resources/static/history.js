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
        <td class="col-audit">
          <button class="btn btn-ghost btn-replay" data-round="${r.roundId}">Replay</button>
          <button class="btn btn-ghost btn-share" data-round="${r.roundId}"
                  title="Mint a public, signed, expiring link to this round">Share proof</button>
        </td>
      </tr>
      <tr class="replay-row hidden" data-replay-for="${r.roundId}">
        <td colspan="9"><div class="replay-panel"></div></td>
      </tr>`;
  }).join("");

  els.body.innerHTML = `
    <table class="history-table">
      <thead>
        <tr>
          <th>Time</th><th>Game</th><th>Context</th>
          <th class="num">Bet</th><th class="num">Win</th><th class="num">Net</th>
          <th>Outcome</th><th>Round Id</th><th>Audit</th>
        </tr>
      </thead>
      <tbody>${rows}</tbody>
    </table>`;

  for (const btn of els.body.querySelectorAll(".btn-replay")) {
    btn.addEventListener("click", () => runReplay(btn.dataset.round, btn));
  }
  for (const btn of els.body.querySelectorAll(".btn-share")) {
    btn.addEventListener("click", () => shareReplay(btn.dataset.round, btn));
  }
}

/* ------------------------------------------------------------------- share */

/**
 * Mint a public proof link for one round (POST /api/v1/admin/replay/{roundId}/share) and put it on the
 * clipboard.
 *
 * The server replays the round before it signs anything, so a link only exists for a round that
 * reconstructs — you cannot hand someone a URL that fails in front of them. The returned link is
 * anonymous, scoped to this round alone, and expires; it is the one artifact of this project that works
 * for somebody with no account.
 */
async function shareReplay(roundId, btn) {
  const row = els.body.querySelector(`tr[data-replay-for="${roundId}"]`);
  const panel = row.querySelector(".replay-panel");
  row.classList.remove("hidden");
  panel.innerHTML = `<p class="history-loading">Verifying the round, then signing a link…</p>`;

  try {
    const playerId = localStorage.getItem(PLAYER_KEY);
    const token = await mintToken(playerId);
    const res = await fetch(`/api/v1/admin/replay/${encodeURIComponent(roundId)}/share`, {
      method: "POST",
      headers: { Authorization: "Bearer " + token },
    });
    const body = await res.json();
    if (!res.ok) throw new Error(body.message || `HTTP ${res.status}`);

    // Clipboard access needs a secure context (https or localhost). Where it is unavailable the URL is
    // still rendered and selectable, so the feature degrades to "copy it yourself" rather than failing.
    let copied = false;
    try {
      await navigator.clipboard.writeText(body.url);
      copied = true;
    } catch { /* fall through to the visible URL below */ }

    panel.innerHTML = renderShare(body, copied);
    toast(copied ? "Proof link copied to clipboard" : "Proof link ready", "ok");
  } catch (e) {
    panel.innerHTML = `<p class="history-error">Could not mint a link: ${e.message}</p>`;
    toast("Share link failed", "error");
  }
}

function renderShare(link, copied) {
  const hours = Math.round((link.ttlSeconds || 0) / 3600);
  const verified = link.verifiedMatrixMatches && link.verifiedTotalWinMatches;
  return `
    <div class="share-panel">
      <div class="share-head">
        <span class="badge ${verified ? "badge-win" : "badge-loss"}">
          ${verified ? "✓ Verified at mint" : "✗ Did not reconstruct"}</span>
        <span class="share-note">Anonymous · this round only · expires in ${hours}h</span>
      </div>
      <div class="share-url">
        <input type="text" readonly value="${link.url}" aria-label="Public replay link"
               onclick="this.select()" />
        <a class="btn btn-ghost" href="${link.url}" target="_blank" rel="noopener noreferrer">Open</a>
      </div>
      <p class="share-hint">
        ${copied ? "Copied to your clipboard. " : ""}Opens for anyone, with no account — the page
        rebuilds the round from its recorded RNG draws and shows the verdict.
      </p>
    </div>`;
}

/* ------------------------------------------------------------------ replay */

/**
 * Reconstruct a round from its persisted RNG draws (POST /api/v1/admin/replay/{roundId}) and show the
 * verdict inline.
 *
 * This is the audit story the whole replay infrastructure exists for, made visible: the server re-runs
 * the recorded draws through the same engine and reports whether every grid came back identical. For a
 * cascading round that means every drop, including the refills — which is the strictest check in the
 * system, since a refill drawn outside the round's RNG would leave the sequence unreproducible.
 */
async function runReplay(roundId, btn) {
  const row = els.body.querySelector(`tr[data-replay-for="${roundId}"]`);
  const panel = row.querySelector(".replay-panel");

  if (!row.classList.contains("hidden")) {
    row.classList.add("hidden");
    btn.textContent = "Replay";
    return;
  }
  row.classList.remove("hidden");
  btn.textContent = "Hide";
  panel.innerHTML = `<p class="history-loading">Reconstructing round from persisted RNG draws…</p>`;

  try {
    const playerId = localStorage.getItem(PLAYER_KEY);
    const token = await mintToken(playerId);
    const res = await fetch(`/api/v1/admin/replay/${encodeURIComponent(roundId)}`, {
      method: "POST",
      headers: { Authorization: "Bearer " + token },
    });
    const body = await res.json();
    // The server explains why a round cannot be reconstructed; showing that beats "HTTP 409".
    if (!res.ok) throw new Error(body.message || `HTTP ${res.status}`);
    panel.innerHTML = renderReplay(body);
  } catch (e) {
    panel.innerHTML = `<p class="history-error">Cannot replay this round: ${e.message}</p>`;
    toast("Replay unavailable for this round", "error");
  }
}

function renderReplay(r) {
  const verdict = (ok, label) =>
    `<span class="badge ${ok ? "badge-win" : "badge-loss"}">${ok ? "✓" : "✗"} ${label}</span>`;

  const steps = r.reconstructedSequence || [];
  const grids = steps.map((s, i) => `
    <div class="replay-step">
      <div class="replay-step-head">
        ${steps.length > 1 ? (i === 0 ? "Drop 1" : `Tumble ${i}`) : "Grid"}
        ${s.multiplier != null && Number(s.multiplier) !== 1 ? ` · ×${Number(s.multiplier)}` : ""}
        ${Number(s.stepWin || 0) > 0 ? ` · ${fmt(s.stepWin)}` : ""}
      </div>
      ${renderGrid(s.grid)}
      <div class="replay-step-draws">draws: [${(s.stopPositions || []).join(", ")}]</div>
    </div>`).join("");

  return `
    <div class="replay-verdicts">
      ${verdict(r.matrixMatches, steps.length > 1
          ? `All ${steps.length} drops reconstructed bit-exact`
          : "Grid reconstructed bit-exact")}
      ${verdict(r.totalWinMatches, `Win matches (${fmt(r.originalTotalWin)})`)}
      <span class="badge badge-power">${(r.rngDraws || []).length} RNG draws replayed</span>
      <span class="badge badge-power">${r.reelStripSet}</span>
    </div>
    <div class="replay-steps">${grids}</div>`;
}

/** The reconstructed board, drawn from the symbol ids the engine produced. */
function renderGrid(matrix) {
  if (!Array.isArray(matrix)) return "";
  const rows = matrix.map((row) =>
    `<tr>${row.map((id) => `<td class="replay-cell">${id}</td>`).join("")}</tr>`).join("");
  return `<table class="replay-grid"><tbody>${rows}</tbody></table>`;
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
