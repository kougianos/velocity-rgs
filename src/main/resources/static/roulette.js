"use strict";

/* =========================================================================
 * Velocity RGS - Roulette game client (vanilla JS).
 * Builds on game-core.js (API, token, toast, info modal). Renders a European
 * single-zero wheel (cosmetic <canvas>) + a clickable betting table, collects
 * the player's chips, and sends them to the server which is the sole authority
 * on the outcome. No game logic lives here - the wheel only animates to the
 * server's winning number. Entry point: initRouletteGame(game).
 *
 * Wrapped in an IIFE so it can co-exist with slot.js on the game page without
 * global name collisions. Only window.initRouletteGame is exposed.
 * ======================================================================= */

(() => {

/** The physical pocket order of a European wheel (clockwise from 0) - cosmetic, for the spinning canvas. */
const WHEEL_ORDER = [
  0, 32, 15, 19, 4, 21, 2, 25, 17, 34, 6, 27, 13, 36, 11, 30, 8, 23,
  10, 5, 24, 16, 33, 1, 20, 14, 31, 9, 22, 18, 29, 7, 28, 12, 35, 3, 26,
];

/** Canvas pocket colours (CSS variables can't be read inside a canvas). */
const WHEEL_FILL = { RED: "#c0392b", BLACK: "#15191f", GREEN: "#1e7d34" };

let META = null;
let COLOR = {};        // number -> "RED" | "BLACK" | "GREEN"
let BET_TYPES = {};    // kind -> { payout, label }
let BET_VALUES = [];   // chip denominations
let MAX_SPOT = Infinity;
let MAX_TOTAL = Infinity;
let SPIN_MS = 3600;

const state = {
  token: null,
  playerId: null,
  sessionId: null,
  sessionVersion: 0,
  balance: 0,
  selectedChip: 0,
  bets: new Map(),     // spotKey -> { kind, number, amount }
  lastBets: null,      // snapshot for "Rebet"
  spinning: false,
  wheelAngle: 0,
};

const spotEls = new Map(); // spotKey -> table cell element (for markers + highlights)

const $ = (id) => document.getElementById(id);
const els = {};

/* ----------------------------------------------------------------- config */

function applyConfig(game) {
  META = game;
  const r = game.roulette || {};
  COLOR = {};
  for (const p of r.pockets || []) COLOR[p.number] = p.color;
  BET_TYPES = {};
  for (const b of r.betTypes || []) BET_TYPES[b.kind] = { payout: b.payout, label: b.label };
  BET_VALUES = (game.betValues || []).map(Number).filter((v) => v > 0).sort((a, b) => a - b);
  state.selectedChip = Number(game.defaultBet) || BET_VALUES[0] || 1;
  MAX_SPOT = Number(r.maxBetPerSpot) || Infinity;
  MAX_TOTAL = Number(r.maxTotalBet) || Infinity;
  if (Number(game.spinDurationMillis) > 0) SPIN_MS = Number(game.spinDurationMillis);
}

/* ----------------------------------------------------------------- chips */

function renderChips() {
  els.chipRow.innerHTML = "";
  for (const v of BET_VALUES) {
    const chip = document.createElement("button");
    chip.className = "chip";
    chip.textContent = fmt(v);
    chip.classList.toggle("is-selected", v === state.selectedChip);
    chip.addEventListener("click", () => {
      state.selectedChip = v;
      renderChips();
    });
    els.chipRow.appendChild(chip);
  }
}

/* ----------------------------------------------------------------- table */

/** True if the game offers this bet kind (so we never render a spot the server would reject). */
const offers = (kind) => Object.prototype.hasOwnProperty.call(BET_TYPES, kind);

function cellColorClass(number) {
  return "c-" + (COLOR[number] || "BLACK").toLowerCase();
}

function makeSpot(label, kind, number, extraClass) {
  const el = document.createElement("div");
  el.className = "rt-cell " + (extraClass || "");
  el.dataset.spot = spotKey(kind, number);
  const cap = document.createElement("span");
  cap.className = "rt-cap";
  cap.textContent = label;
  el.appendChild(cap);
  const marker = document.createElement("span");
  marker.className = "rt-marker";
  el.appendChild(marker);
  el.addEventListener("click", () => placeBet(kind, number));
  spotEls.set(spotKey(kind, number), el);
  return el;
}

function spotKey(kind, number) {
  return kind === "STRAIGHT" ? `STRAIGHT:${number}` : kind;
}

function renderTable() {
  spotEls.clear();
  const table = els.table;
  table.innerHTML = "";

  // --- main: zero | 12×3 numbers | three column bets ---
  const main = document.createElement("div");
  main.className = "rt-main";

  const zero = makeSpot("0", "STRAIGHT", 0, "rt-zero " + cellColorClass(0));
  zero.style.gridRow = "1 / span 3";
  zero.style.gridColumn = "1";
  main.appendChild(zero);

  for (let c = 0; c < 12; c++) {
    for (let row = 0; row < 3; row++) {
      const number = 3 * c + (3 - row);
      const cell = makeSpot(String(number), "STRAIGHT", number, "rt-num " + cellColorClass(number));
      cell.style.gridColumn = String(c + 2);
      cell.style.gridRow = String(row + 1);
      main.appendChild(cell);
    }
  }

  // Column bets (2:1) - top row covers ...,36 (COLUMN_3); middle COLUMN_2; bottom COLUMN_1.
  const colKinds = ["COLUMN_3", "COLUMN_2", "COLUMN_1"];
  colKinds.forEach((kind, row) => {
    if (!offers(kind)) return;
    const cell = makeSpot("2:1", kind, null, "rt-col");
    cell.style.gridColumn = "14";
    cell.style.gridRow = String(row + 1);
    main.appendChild(cell);
  });
  table.appendChild(main);

  // --- dozens ---
  const dozens = document.createElement("div");
  dozens.className = "rt-dozens";
  dozens.appendChild(spacer());
  [["DOZEN_1", "1st 12"], ["DOZEN_2", "2nd 12"], ["DOZEN_3", "3rd 12"]].forEach(([kind, label]) => {
    dozens.appendChild(offers(kind) ? makeSpot(label, kind, null, "rt-outside-cell") : spacer());
  });
  dozens.appendChild(spacer());
  table.appendChild(dozens);

  // --- outside even-money row ---
  const outside = document.createElement("div");
  outside.className = "rt-outside";
  outside.appendChild(spacer());
  [["LOW", "1–18"], ["EVEN", "Even"], ["RED", "Red", "rt-red"], ["BLACK", "Black", "rt-black"],
   ["ODD", "Odd"], ["HIGH", "19–36"]].forEach(([kind, label, cls]) => {
    outside.appendChild(offers(kind)
      ? makeSpot(label, kind, null, "rt-outside-cell " + (cls || ""))
      : spacer());
  });
  outside.appendChild(spacer());
  table.appendChild(outside);

  renderMarkers();
}

function spacer() {
  const s = document.createElement("div");
  s.className = "rt-spacer";
  return s;
}

/* ----------------------------------------------------------------- bets */

function placeBet(kind, number) {
  if (state.spinning) return;
  if (!offers(kind)) return;
  const key = spotKey(kind, number);
  const existing = state.bets.get(key);
  const current = existing ? existing.amount : 0;
  if (current + state.selectedChip > MAX_SPOT + 1e-9) {
    toast(`Max ${fmt(MAX_SPOT)} per spot`, "error");
    return;
  }
  if (currentTotal() + state.selectedChip > MAX_TOTAL + 1e-9) {
    toast(`Max ${fmt(MAX_TOTAL)} total bet`, "error");
    return;
  }
  state.bets.set(key, { kind, number, amount: current + state.selectedChip });
  clearHighlights();
  renderMarkers();
  renderSummary();
}

function currentTotal() {
  let t = 0;
  for (const b of state.bets.values()) t += b.amount;
  return t;
}

function renderMarkers() {
  for (const [key, el] of spotEls) {
    const bet = state.bets.get(key);
    const marker = el.querySelector(".rt-marker");
    if (bet) {
      marker.textContent = fmt(bet.amount);
      el.classList.add("has-bet");
    } else {
      marker.textContent = "";
      el.classList.remove("has-bet");
    }
  }
}

function renderSummary() {
  els.total.textContent = fmt(currentTotal());
  els.betCount.textContent = String(state.bets.size);
  els.spinBtn.disabled = state.spinning || state.bets.size === 0;
}

function clearBets() {
  state.bets.clear();
  clearHighlights();
  renderMarkers();
  renderSummary();
}

function rebet() {
  if (!state.lastBets || state.spinning) return;
  state.bets = new Map(state.lastBets.map((b) => [spotKey(b.kind, b.number), { ...b }]));
  clearHighlights();
  renderMarkers();
  renderSummary();
}

/* ----------------------------------------------------------------- wheel canvas */

function wheelColor(number) {
  return WHEEL_FILL[COLOR[number] || "BLACK"];
}

function drawWheel(rotation) {
  const c = els.wheel;
  const ctx = c.getContext("2d");
  const W = c.width, H = c.height, cx = W / 2, cy = H / 2, R = Math.min(cx, cy) - 6;
  const n = WHEEL_ORDER.length;
  const seg = (Math.PI * 2) / n;

  ctx.clearRect(0, 0, W, H);
  ctx.save();
  ctx.translate(cx, cy);
  ctx.rotate(rotation);
  for (let i = 0; i < n; i++) {
    const num = WHEEL_ORDER[i];
    const a0 = i * seg - seg / 2 - Math.PI / 2;
    const a1 = a0 + seg;
    ctx.beginPath();
    ctx.moveTo(0, 0);
    ctx.arc(0, 0, R, a0, a1);
    ctx.closePath();
    ctx.fillStyle = wheelColor(num);
    ctx.fill();
    ctx.strokeStyle = "rgba(0,0,0,0.35)";
    ctx.stroke();

    ctx.save();
    ctx.rotate((a0 + a1) / 2);
    ctx.textAlign = "right";
    ctx.textBaseline = "middle";
    ctx.fillStyle = "#fff";
    ctx.font = "bold 11px 'Segoe UI', sans-serif";
    ctx.fillText(String(num), R - 6, 0);
    ctx.restore();
  }
  ctx.restore();

  // hub
  ctx.beginPath();
  ctx.arc(cx, cy, R * 0.52, 0, Math.PI * 2);
  ctx.fillStyle = "#0a0e14";
  ctx.fill();
  ctx.strokeStyle = "#2d3543";
  ctx.lineWidth = 2;
  ctx.stroke();

  // top pointer
  ctx.beginPath();
  ctx.moveTo(cx - 9, 1);
  ctx.lineTo(cx + 9, 1);
  ctx.lineTo(cx, 17);
  ctx.closePath();
  ctx.fillStyle = "#f5c518";
  ctx.fill();
}

/** Spin the wheel so `winning` lands under the top pointer, then resolve. */
function spinWheelTo(winning) {
  return new Promise((resolve) => {
    const n = WHEEL_ORDER.length;
    const seg = (Math.PI * 2) / n;
    const index = Math.max(0, WHEEL_ORDER.indexOf(winning));
    const target = Math.PI * 2 * 5 - index * seg; // 5 full turns, then align
    const start = state.wheelAngle % (Math.PI * 2);
    const from = start;
    const to = target;
    const t0 = performance.now();
    const dur = SPIN_MS;
    const easeOut = (t) => 1 - Math.pow(1 - t, 3);

    function frame(now) {
      const t = Math.min(1, (now - t0) / dur);
      state.wheelAngle = from + (to - from) * easeOut(t);
      drawWheel(state.wheelAngle);
      if (t < 1) {
        requestAnimationFrame(frame);
      } else {
        state.wheelAngle = to;
        drawWheel(to);
        resolve();
      }
    }
    requestAnimationFrame(frame);
  });
}

/* ----------------------------------------------------------------- result / highlight */

function clearHighlights() {
  for (const el of spotEls.values()) el.classList.remove("is-winning", "won");
  els.result.classList.add("is-empty");
  els.result.removeAttribute("data-color");
  els.resultNumber.textContent = "-";
  els.resultLabel.textContent = "Place your bets";
  els.win.classList.add("is-empty");
  els.winAmount.textContent = "0.00";
}

function showResult(resp) {
  const num = resp.winningNumber;
  const color = resp.winningColor;
  els.result.classList.remove("is-empty");
  els.result.dataset.color = color;
  els.resultNumber.textContent = String(num);
  els.resultLabel.textContent = color;

  // Highlight the winning straight number and any winning spots the player covered.
  const numEl = spotEls.get(spotKey("STRAIGHT", num));
  if (numEl) numEl.classList.add("is-winning");
  for (const wb of resp.winningBets || []) {
    if (wb.won) {
      const el = spotEls.get(spotKey(wb.type, wb.number));
      if (el) el.classList.add("won");
    }
  }

  const win = Number(resp.totalWin || 0);
  els.winAmount.textContent = fmt(win);
  els.win.classList.toggle("is-empty", win <= 0);
}

/* ----------------------------------------------------------------- flows */

function setBusy(busy) {
  state.spinning = busy;
  els.spinBtn.disabled = busy || state.bets.size === 0;
  els.clearBets.disabled = busy;
  els.rebet.disabled = busy || !state.lastBets;
}

async function refreshBalance() {
  try {
    const bal = await api("/api/v1/wallet/balance", { track: false });
    state.balance = Number(bal.balance);
    renderHud();
  } catch (e) {
    // non-fatal
  }
}

function renderHud() {
  els.balance.textContent = `${fmt(state.balance)} ${CURRENCY}`;
  els.gameState.textContent = "ROULETTE";
  els.freeSpins.textContent = "-";
}

async function boot(forceNewPlayer = false) {
  try {
    setBusy(true);
    state.playerId = resolvePlayerId(forceNewPlayer);
    state.sessionId = crypto.randomUUID();
    state.token = await mintDevToken({ playerId: state.playerId, sessionId: state.sessionId });
    window.__authToken = state.token;
    // Responsible Gaming watch: the reality check is measured in elapsed time, so it has to be
    // able to fire on a player who has stopped clicking.
    if (typeof startRgWatch === "function") startRgWatch();

    const init = await api("/api/v1/roulette/init", {
      method: "POST",
      body: { gameId: META.gameId, currency: CURRENCY },
    });
    state.sessionId = init.sessionId;
    state.sessionVersion = init.sessionVersion;
    state.balance = Number(init.balance);
    renderHud();
    logResponse("roulette/init", init);
    toast(`Demo player ${state.playerId} ready`, "success");
  } catch (e) {
    handleError("Boot failed", e);
  } finally {
    setBusy(false);
    renderSummary();
  }
}

async function doSpin() {
  if (state.bets.size === 0 || state.spinning) return;
  const bets = [...state.bets.values()].map((b) => ({
    type: b.kind,
    number: b.kind === "STRAIGHT" ? b.number : undefined,
    amount: b.amount,
  }));
  try {
    setBusy(true);
    clearHighlights();
    const resp = await api("/api/v1/roulette/spin", {
      method: "POST",
      idempotency: true,
      body: {
        gameId: META.gameId,
        sessionId: state.sessionId,
        sessionVersion: state.sessionVersion,
        bets,
      },
    });
    state.sessionVersion = resp.sessionVersion;
    await spinWheelTo(resp.winningNumber);
    showResult(resp);
    if (typeof resp.balance === "number") {
      state.balance = Number(resp.balance);
      renderHud();
    } else {
      await refreshBalance();
    }
    logResponse("roulette/spin", resp);
    const win = Number(resp.totalWin || 0);
    toast(win > 0
      ? `${resp.winningNumber} ${resp.winningColor} - won ${fmt(win)} ${CURRENCY}`
      : `${resp.winningNumber} ${resp.winningColor} - no win`,
      win > 0 ? "success" : "");
    // Remember the layout for Rebet, then clear the table for the next round.
    state.lastBets = [...state.bets.values()].map((b) => ({ ...b }));
    state.bets.clear();
    renderMarkers();
    renderSummary();
  } catch (e) {
    handleError("Spin failed", e);
  } finally {
    setBusy(false);
  }
}

/* ----------------------------------------------------------------- wiring */

function bindEvents() {
  els.spinBtn.addEventListener("click", doSpin);
  els.clearBets.addEventListener("click", clearBets);
  els.rebet.addEventListener("click", rebet);
  els.resetSession.addEventListener("click", () => boot(true));
  els.clearLog.addEventListener("click", () => { const l = $("log"); if (l) l.textContent = ""; });
}

/**
 * Roulette entry point - called by the game-page bootstrap when the resolved catalog game is ROULETTE.
 * Shared chrome + info modal are already applied by the bootstrap.
 */
function initRouletteGame(game) {
  Object.assign(els, {
    wheel: $("wheel"),
    result: $("rouletteResult"),
    resultNumber: $("resultNumber"),
    resultLabel: $("resultLabel"),
    win: $("rouletteWin"),
    winAmount: $("rouletteWinAmount"),
    chipRow: $("chipRow"),
    table: $("rouletteTable"),
    total: $("rouletteTotal"),
    betCount: $("rouletteBetCount"),
    spinBtn: $("spinRoulette"),
    clearBets: $("clearBets"),
    rebet: $("rebet"),
    balance: $("balance"),
    gameState: $("gameState"),
    freeSpins: $("freeSpins"),
    resetSession: $("resetSession"),
    clearLog: $("clearLog"),
  });
  applyConfig(game);
  bindEvents();
  renderChips();
  renderTable();
  renderSummary();
  drawWheel(0);
  boot();
}

window.initRouletteGame = initRouletteGame;

})();
