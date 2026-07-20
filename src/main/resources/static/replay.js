"use strict";

/* =========================================================================
 * Velocity RGS - public round replay (§3.1).
 *
 * The one page here that a stranger can open: no token in memory, no session,
 * no login. It reads the signed link token off its own URL, asks the server to
 * rebuild that single round from the RNG draws recorded when it was played, and
 * renders the reconstruction beside the evidence.
 *
 * Everything shown is server-derived. This file computes no outcome, no win and
 * no verdict - it draws what GET /api/v1/public/replay/{token} returned, which is
 * the same discipline the game client follows and the reason the page is worth
 * anything as proof.
 * ======================================================================= */

const API = "/api/v1/public/replay/";
const STEP_MS = 900;

const root = document.getElementById("replayRoot");
const prefersReducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

let SYMBOLS = {};        // { symbolId: { glyph, name } } for the round's game, when the catalog knows it
let GAME_TITLE = null;   // the game's display name, when the catalog knows it
let playbackTimer = null;

/* ------------------------------------------------------------------ token */

/**
 * The token is the path segment of /r/<token>. The ?t=<token> form is accepted too so a link that
 * has been through something that mangles paths still resolves.
 */
function readToken() {
  const fromPath = window.location.pathname.match(/^\/r\/(.+)$/);
  if (fromPath) return decodeURIComponent(fromPath[1]);
  return new URLSearchParams(window.location.search).get("t");
}

/* ------------------------------------------------------------------ fetch */

async function fetchReplay(token) {
  const res = await fetch(API + encodeURIComponent(token), { headers: { Accept: "application/json" } });
  const body = await res.json().catch(() => ({}));
  if (!res.ok) {
    // The server distinguishes "expired" from "edited" from "round gone"; carry its code through so
    // the page can say which of those happened instead of collapsing them into one failure.
    const err = new Error(body.message || `HTTP ${res.status}`);
    err.code = body.code;
    err.status = res.status;
    throw err;
  }
  return body;
}

/**
 * Presentation for the round's game - name and symbol glyphs - from the public catalog.
 *
 * Purely cosmetic, and the catalog is a second request that can fail on its own, so this degrades to
 * raw ids rather than taking the page down: the proof does not depend on knowing what the symbols
 * look like. resolveGame() falls back to the first game in the catalog, hence the explicit id check -
 * mislabelling the round would be worse than not labelling it.
 */
async function loadGameMeta(gameId) {
  try {
    const game = resolveGame(await fetchCatalog(), gameId);
    if (game && game.gameId === gameId) {
      SYMBOLS = buildSymbolMap(game);
      GAME_TITLE = game.title;
    }
  } catch {
    SYMBOLS = {};
    GAME_TITLE = null;
  }
}

/* ------------------------------------------------------------------ format */

function fmtAmount(value, currency) {
  const n = Number(value ?? 0);
  if (!currency) return n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  try {
    return n.toLocaleString(undefined, { style: "currency", currency });
  } catch {
    return `${n.toFixed(2)} ${currency}`;
  }
}

function fmtDateTime(iso) {
  if (!iso) return "-";
  const d = new Date(iso);
  return isNaN(d) ? iso : d.toLocaleString();
}

/** "in 23 hours" / "in 4 minutes" / "expired" - the link's remaining life, stated plainly. */
function fmtRemaining(iso) {
  if (!iso) return null;
  const ms = new Date(iso).getTime() - Date.now();
  if (isNaN(ms)) return null;
  if (ms <= 0) return "expired";
  const hours = Math.floor(ms / 3600000);
  if (hours >= 1) return `${hours} hour${hours === 1 ? "" : "s"}`;
  const minutes = Math.max(1, Math.floor(ms / 60000));
  return `${minutes} minute${minutes === 1 ? "" : "s"}`;
}

const titleise = (s) => String(s || "").replace(/_/g, " ").toLowerCase().replace(/\b\w/g, (c) => c.toUpperCase());

/* ------------------------------------------------------------------ render */

function render(r) {
  const cascading = r.stepCount > 1;
  const net = Number(r.originalTotalWin || 0) - Number(r.betAmount || 0);
  const verified = r.matrixMatches && r.totalWinMatches;
  const remaining = fmtRemaining(r.linkExpiresAt);

  root.innerHTML = `
    ${renderVerdict(r, verified, cascading)}

    <section class="rp-facts" aria-label="Round facts">
      ${fact("Game", esc(GAME_TITLE || r.gameId), "is-strong")}
      ${fact("Stake", fmtAmount(r.betAmount, r.currency))}
      ${fact("Paid", fmtAmount(r.originalTotalWin, r.currency), Number(r.originalTotalWin) > 0 ? "is-gold" : "")}
      ${fact("Net", `${net >= 0 ? "+" : "−"}${fmtAmount(Math.abs(net), r.currency)}`, net >= 0 ? "is-pos" : "is-neg")}
      ${fact("Played", fmtDateTime(r.roundPlayedAt))}
      ${fact("Reel set", titleise(r.reelStripSet))}
    </section>

    ${renderPlayback(r, cascading)}
    ${renderDraws(r)}
    ${renderExplainer(r, remaining)}
  `;

  wirePlayback(r);
}

function fact(label, value, cls = "") {
  return `<div class="rp-fact"><span class="rp-fact-k vx-lab">${label}</span>
          <span class="rp-fact-v vx-num ${cls}">${value}</span></div>`;
}

/**
 * The headline. States the claim in one sentence and backs it with the two independent checks the
 * server ran - the boards and the money - because "verified" on its own is a word, not evidence.
 */
function renderVerdict(r, verified, cascading) {
  const drawCount = (r.rngDraws || []).length;
  const claim = cascading
    ? `All ${r.stepCount} drops of this round were rebuilt from ${drawCount} recorded RNG draws
       and every board came back identical to the one stored when it was played.`
    : `This round was rebuilt from ${drawCount} recorded RNG draws and the board came back identical
       to the one stored when it was played.`;

  return `
    <section class="rp-verdict ${verified ? "is-ok" : "is-bad"}">
      <div class="rp-verdict-mark" aria-hidden="true">${verified ? "✓" : "✗"}</div>
      <div class="rp-verdict-copy">
        <span class="vx-lab">Deterministic replay · verified just now</span>
        <h1>${verified ? "Reconstruction matched" : "Reconstruction diverged"}</h1>
        <p>${claim}</p>
        <div class="rp-verdict-checks">
          ${check(r.matrixMatches, cascading ? `${r.stepCount} boards match` : "Board matches")}
          ${check(r.totalWinMatches, `Payout matches (${fmtAmount(r.originalTotalWin, r.currency)})`)}
          <span class="rp-check is-note vx-num">${drawCount} draws replayed</span>
        </div>
      </div>
      <div class="rp-verdict-id">
        <span class="vx-lab">Round</span>
        <code>${esc(r.roundId)}</code>
      </div>
    </section>`;
}

function check(ok, label) {
  return `<span class="rp-check ${ok ? "is-ok" : "is-bad"}">${ok ? "✓" : "✗"} ${label}</span>`;
}

/** Every drop, in order, with the draws that produced it. The round's visual record. */
function renderPlayback(r, cascading) {
  const steps = (r.steps || []).map((s, i) => renderStep(s, i, r, cascading)).join("");
  return `
    <section class="rp-block">
      <div class="rp-block-head">
        <h2>${cascading ? "The tumble, drop by drop" : "The board"}</h2>
        ${cascading ? `<button class="rp-play" id="rpPlay" type="button">
             <span class="rp-play-i" aria-hidden="true">▶</span><span class="rp-play-t">Play the sequence</span>
           </button>` : ""}
      </div>
      ${cascading ? `<p class="rp-block-sub">Each drop clears its winning cells and refills them from the
        same draw stream, paying at a rising multiplier. A replay that reproduced only the opening board
        would prove nothing about the drops after it.</p>` : ""}
      <div class="rp-steps${cascading ? " is-sequence" : ""}">${steps}</div>
    </section>`;
}

function renderStep(step, i, r, cascading) {
  const cleared = new Set((step.clearedPositions || []).map(([row, col]) => `${row}:${col}`));
  const mult = Number(step.multiplier || 1);
  const win = Number(step.stepWin || 0);

  const label = cascading ? (i === 0 ? "Drop 1" : `Tumble ${i}`) : "Result";
  const wins = (step.winLines || []).map((w) => {
    const sym = SYMBOLS[w.symbolId];
    const name = sym ? sym.name : `#${w.symbolId}`;
    const how = w.lineId != null ? `line ${w.lineId}` : `${w.ways} way${w.ways === 1 ? "" : "s"}`;
    return `<li>${esc(name)} ×${w.count} <span class="rp-win-how">${how}</span>
            <span class="rp-win-pay vx-num">${fmtAmount(w.payout, r.currency)}</span></li>`;
  }).join("");

  return `
    <article class="rp-step" data-step="${i}">
      <header class="rp-step-head">
        <span class="rp-step-n vx-lab">${label}</span>
        ${mult !== 1 ? `<span class="rp-step-mult vx-num">×${mult}</span>` : ""}
        ${win > 0 ? `<span class="rp-step-win vx-num">${fmtAmount(win, r.currency)}</span>` : ""}
        <span class="rp-step-ok" title="${step.matches
          ? "Rebuilt board is identical to the persisted one"
          : "Rebuilt board differs from the persisted one"}">${step.matches ? "✓" : "✗"}</span>
      </header>
      ${renderGrid(step.grid, cleared)}
      ${wins ? `<ul class="rp-step-wins">${wins}</ul>` : `<p class="rp-step-nowin">No win on this board</p>`}
      <footer class="rp-step-draws vx-num">
        <span class="vx-lab">${i === 0 ? "Reel stops" : "Refill draws"}</span>
        ${(step.stopPositions || []).join(" · ") || "-"}
      </footer>
    </article>`;
}

/**
 * One board, drawn with the game's own symbol glyphs from the catalog.
 *
 * `grid` is [row][reel], matching how the engine persists it - so a row here is a row on screen and no
 * transposition is needed or wanted.
 */
function renderGrid(matrix, cleared) {
  if (!Array.isArray(matrix)) return "";
  const rows = matrix.map((row, r) => `<tr>${row.map((id, c) => {
    const meta = SYMBOLS[id] || { glyph: String(id), name: `Symbol ${id}` };
    const hit = cleared.has(`${r}:${c}`);
    return `<td class="rp-cell${hit ? " is-hit" : ""}" title="${esc(meta.name)}">
              <span class="rp-cell-g">${esc(meta.glyph)}</span></td>`;
  }).join("")}</tr>`).join("");
  return `<table class="rp-grid"><tbody>${rows}</tbody></table>`;
}

/**
 * The raw draws. This is the part that makes the page checkable rather than merely reassuring: the
 * numbers below are what the server fed back through the engine to produce every board above.
 */
function renderDraws(r) {
  const draws = r.rngDraws || [];
  if (!draws.length) return "";
  const chips = draws.map((d) => `
    <li class="rp-draw">
      <span class="rp-draw-seq vx-lab">${d.sequence}</span>
      <span class="rp-draw-val vx-num">${d.value}</span>
      <span class="rp-draw-bound vx-num">/${d.boundExclusive}</span>
    </li>`).join("");

  return `
    <section class="rp-block">
      <div class="rp-block-head"><h2>The recorded draws</h2></div>
      <p class="rp-block-sub">Every value the RNG produced for this round, in the order the engine
        consumed it - <span class="vx-num">value / bound</span>. These were written at round commit and
        are what the reconstruction above was driven by.</p>
      <ul class="rp-draws">${chips}</ul>
    </section>`;
}

function renderExplainer(r, remaining) {
  return `
    <section class="rp-explain">
      <div class="rp-explain-copy">
        <h2>Why this can be checked</h2>
        <p>Velocity decides every outcome server-side and stores the RNG draws that produced it. Nothing
          about the result lives in the browser, so a round can be re-run later through the same engine
          and must land on the same board - or the mismatch is visible, as it would be above.</p>
        <p class="rp-explain-meta">
          Game math <code>${esc(r.gameId)} · ${esc(r.mathVersion)}</code>. Reconstruction ran when you
          opened this page, not when the link was made.
          ${remaining ? `This link stops working in <strong>${remaining}</strong>.` : ""}
          It grants this one round and carries no player identity.
        </p>
      </div>
      <a class="vx-cta rp-cta" href="/">Explore the platform <span class="arw">→</span></a>
    </section>`;
}

/* ------------------------------------------------------------------ playback */

/** Walk the drops in order, marking one live at a time, so a tumble reads as a sequence not a row. */
function wirePlayback(r) {
  const btn = document.getElementById("rpPlay");
  if (!btn) return;
  const steps = [...root.querySelectorAll(".rp-step")];

  btn.addEventListener("click", () => {
    if (playbackTimer) return stopPlayback(btn, steps);
    let i = 0;
    btn.classList.add("is-playing");
    btn.querySelector(".rp-play-i").textContent = "■";
    btn.querySelector(".rp-play-t").textContent = "Stop";

    const tick = () => {
      steps.forEach((el, n) => el.classList.toggle("is-live", n === i));
      steps[i].scrollIntoView({ behavior: prefersReducedMotion ? "auto" : "smooth",
                               block: "nearest", inline: "center" });
      i += 1;
      if (i >= steps.length) {
        playbackTimer = setTimeout(() => stopPlayback(btn, steps), STEP_MS);
        return;
      }
      playbackTimer = setTimeout(tick, STEP_MS);
    };
    tick();
  });
}

function stopPlayback(btn, steps) {
  clearTimeout(playbackTimer);
  playbackTimer = null;
  steps.forEach((el) => el.classList.remove("is-live"));
  btn.classList.remove("is-playing");
  btn.querySelector(".rp-play-i").textContent = "▶";
  btn.querySelector(".rp-play-t").textContent = "Play the sequence";
}

/* ------------------------------------------------------------------ problems */

/**
 * A public link is the one surface a stranger meets in its broken state, so each way it can fail gets
 * its own explanation rather than a status code. Expiry in particular is not an error the visitor can
 * do anything about, and should not read like one.
 */
function renderProblem(err) {
  const cases = {
    REPLAY_LINK_EXPIRED: {
      icon: "⏳",
      title: "This replay link has expired",
      body: `Proof links are deliberately short-lived. The round itself is untouched and still
             reconstructable - ask whoever shared this for a fresh link.`,
    },
    REPLAY_LINK_INVALID: {
      icon: "⚠",
      title: "This replay link isn’t valid",
      body: `The link is signed, and this one doesn’t verify - it was truncated in transit, edited, or
             never issued by this server. Nothing was disclosed.`,
    },
    SESSION_NOT_FOUND: {
      icon: "🔍",
      title: "That round is no longer here",
      body: `The link verified, but the round it points at isn’t on this server anymore. Demo data is
             periodically reset.`,
    },
    ROUND_NOT_REPLAYABLE: {
      icon: "🧩",
      title: "This round can’t be reconstructed",
      body: err.message || `The server has the round but not everything needed to rebuild it.`,
    },
  };

  const c = cases[err.code] || {
    icon: "⚠",
    title: "Couldn’t load this replay",
    body: err.message || "The server didn’t respond as expected. Try again in a moment.",
  };

  root.innerHTML = `
    <section class="rp-problem">
      <div class="rp-problem-icon" aria-hidden="true">${c.icon}</div>
      <h1>${c.title}</h1>
      <p>${c.body}</p>
      <a class="vx-cta rp-cta" href="/">Explore the platform <span class="arw">→</span></a>
    </section>`;
}

/* ------------------------------------------------------------------ boot */

async function load() {
  const token = readToken();
  if (!token) {
    renderProblem({ code: "REPLAY_LINK_INVALID" });
    return;
  }
  try {
    const replay = await fetchReplay(token);
    await loadGameMeta(replay.gameId);
    render(replay);
  } catch (e) {
    renderProblem(e);
  }
}

document.addEventListener("DOMContentLoaded", load);
