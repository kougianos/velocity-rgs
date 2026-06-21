"use strict";

/* =========================================================================
 * Velocity RGS — Demo Test Harness (vanilla JS)
 * Talks directly to the Spring Boot demo backend on the same origin.
 * Auto-mints a demo JWT (PLAYER + ADMIN) so no manual login is required.
 * ======================================================================= */

const CURRENCY = "EUR";

/** Active game resolved from the ?game= query param (falls back to the default). */
const GAME_ID = (() => {
  const requested = new URLSearchParams(window.location.search).get("game");
  return requested && GAME_META[requested] ? requested : DEFAULT_GAME_ID;
})();
const META = gameMeta(GAME_ID);

/** Symbol id -> display metadata for the active game (see games.js). */
const SYMBOLS = META.symbols;

/** Payline coordinates [row, col] indexed by lineId (for win highlighting). */
const PAYLINES = {
  1:  [[0,0],[0,1],[0,2],[0,3],[0,4]],
  2:  [[1,0],[1,1],[1,2],[1,3],[1,4]],
  3:  [[2,0],[2,1],[2,2],[2,3],[2,4]],
  4:  [[0,0],[1,1],[2,2],[1,3],[0,4]],
  5:  [[2,0],[1,1],[0,2],[1,3],[2,4]],
  6:  [[0,0],[0,1],[1,2],[2,3],[2,4]],
  7:  [[2,0],[2,1],[1,2],[0,3],[0,4]],
  8:  [[1,0],[0,1],[1,2],[2,3],[1,4]],
  9:  [[1,0],[2,1],[1,2],[0,3],[1,4]],
  10: [[0,0],[1,1],[1,2],[1,3],[0,4]],
  11: [[2,0],[1,1],[1,2],[1,3],[2,4]],
  12: [[1,0],[0,1],[0,2],[0,3],[1,4]],
  13: [[1,0],[2,1],[2,2],[2,3],[1,4]],
  14: [[0,0],[1,1],[0,2],[1,3],[0,4]],
  15: [[2,0],[1,1],[2,2],[1,3],[2,4]],
  16: [[0,0],[2,1],[0,2],[2,3],[0,4]],
  17: [[2,0],[0,1],[2,2],[0,3],[2,4]],
  18: [[1,0],[1,1],[0,2],[1,3],[1,4]],
  19: [[1,0],[1,1],[2,2],[1,3],[1,4]],
  20: [[0,0],[2,1],[2,2],[2,3],[0,4]],
};

const ROWS = 3;
const COLS = 5;

const state = {
  token: null,
  playerId: null,
  sessionId: null,
  sessionVersion: 0,
  balance: 0,
  currentState: "BASE_GAME",
  availableActions: [],
  // Power Bet: the multiplier comes from the server (init featureFlags); baseBet is the player's
  // chosen per-spin stake before the multiplier is applied. While Power Bet is on, the Bet field is
  // locked to baseBet × multiplier for display, but we always send baseBet to the server — the
  // server is the single authority that applies the multiplier to the debit and the win.
  powerMultiplier: 1.5,
  baseBet: 1.0,
};

/** 2D array of cell elements [row][col]. */
let gridCells = [];

/* ----------------------------------------------------------------- DOM refs */
const $ = (id) => document.getElementById(id);
const els = {
  balance: $("balance"),
  gameState: $("gameState"),
  freeSpins: $("freeSpins"),
  reels: $("reels"),
  winBanner: $("winBanner"),
  winAmount: $("winAmount"),
  winLines: $("winLines"),
  betSize: $("betSize"),
  betControl: $("betControl"),
  betPowerHint: $("betPowerHint"),
  betPowerMult: $("betPowerMult"),
  powerBet: $("powerBet"),
  powerBetToggle: $("powerBetToggle"),
  powerBetMultLabel: $("powerBetMultLabel"),
  spinBtn: $("spinBtn"),
  buyFreeSpins: $("buyFreeSpins"),
  startFeature: $("startFeature"),
  pickPanel: $("pickPanel"),
  pickBoard: $("pickBoard"),
  pickCollected: $("pickCollected"),
  pickTotal: $("pickTotal"),
  pickRemaining: $("pickRemaining"),
  resetSession: $("resetSession"),
  simBet: $("simBet"),
  simBase: $("simBase"),
  simPower: $("simPower"),
  simFs: $("simFs"),
  simStrategy: $("simStrategy"),
  runSim: $("runSim"),
  simReport: $("simReport"),
  adminBalance: $("adminBalance"),
  setBalanceBtn: $("setBalanceBtn"),
  log: $("log"),
  clearLog: $("clearLog"),
  toast: $("toast"),
  brandLogo: $("brandLogo"),
  gameName: $("gameName"),
  gameTagline: $("gameTagline"),
  buyFreeSpinsCost: $("buyFreeSpinsCost"),
  fsModal: $("fsModal"),
  fsModalIcon: $("fsModalIcon"),
  fsModalTitle: $("fsModalTitle"),
  fsModalMessage: $("fsModalMessage"),
  fsModalConfirm: $("fsModalConfirm"),
  fsModalCancel: $("fsModalCancel"),
};

const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * Show a centered modal and resolve to true (confirm) / false (cancel).
 * The cancel button is hidden unless a `cancelLabel` is supplied.
 */
function showModal({ icon = "🎁", title, message, confirmLabel = "OK", cancelLabel }) {
  return new Promise((resolve) => {
    els.fsModalIcon.textContent = icon;
    els.fsModalTitle.textContent = title;
    els.fsModalMessage.textContent = message;
    els.fsModalConfirm.textContent = confirmLabel;
    if (cancelLabel) {
      els.fsModalCancel.textContent = cancelLabel;
      els.fsModalCancel.classList.remove("hidden");
    } else {
      els.fsModalCancel.classList.add("hidden");
    }
    els.fsModal.classList.remove("hidden");
    const close = (value) => {
      els.fsModal.classList.add("hidden");
      els.fsModalConfirm.onclick = null;
      els.fsModalCancel.onclick = null;
      resolve(value);
    };
    els.fsModalConfirm.onclick = () => close(true);
    els.fsModalCancel.onclick = () => close(false);
  });
}

/* ----------------------------------------------------------------- helpers */

class ApiError extends Error {
  constructor(status, payload) {
    super(payload && payload.message ? payload.message : `HTTP ${status}`);
    this.status = status;
    this.payload = payload;
  }
}

async function api(path, { method = "GET", body, idempotency = false } = {}) {
  const headers = { "Content-Type": "application/json" };
  if (state.token) headers["Authorization"] = "Bearer " + state.token;
  if (idempotency) headers["Idempotency-Key"] = crypto.randomUUID();

  const res = await fetch(path, {
    method,
    headers,
    body: body ? JSON.stringify(body) : undefined,
  });

  const raw = await res.text();
  let data = null;
  try { data = raw ? JSON.parse(raw) : null; } catch { data = raw; }

  if (!res.ok) throw new ApiError(res.status, data);
  return data;
}

function fmt(value) {
  const n = Number(value ?? 0);
  return n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function logResponse(label, data) {
  els.log.textContent = `// ${label}  @ ${new Date().toLocaleTimeString()}\n` +
    JSON.stringify(data, null, 2);
}

let toastTimer = null;
function toast(message, kind = "") {
  els.toast.textContent = message;
  els.toast.className = "toast " + kind;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => els.toast.classList.add("hidden"), 3200);
}

function setBusy(busy) {
  if (busy) {
    els.spinBtn.disabled = true;
    els.buyFreeSpins.disabled = true;
    els.buyPickCollect.disabled = true;
    els.startFeature.disabled = true;
    return;
  }
  // When clearing the busy state, re-derive enablement from the legal actions
  // instead of blanket-enabling — otherwise SPIN becomes clickable in states like
  // FREE_SPINS_AWAITING where only START_FREE_SPINS is allowed.
  els.startFeature.disabled = false;
  renderActions();
}

/* -------------------------------------------------------------- power bet */

/** The per-spin stake to send to the server — always the base bet; the server applies the ×N. */
function betForRequest() {
  return els.powerBet.checked ? state.baseBet : Number(els.betSize.value);
}

/** Push the live multiplier (from the server) into the on-screen labels. */
function updatePowerMultLabels() {
  const m = state.powerMultiplier;
  if (els.powerBetMultLabel) els.powerBetMultLabel.textContent = m;
  if (els.betPowerMult) els.betPowerMult.textContent = m;
}

/**
 * Reflect the Power Bet toggle into the Bet field. While enabled the field is locked and shows the
 * effective (multiplied) stake; we stash the chosen base bet so we can restore it when disabled and
 * still send the base value to the server.
 */
function applyPowerBetState() {
  const on = els.powerBet.checked;
  if (on) {
    state.baseBet = Number(els.betSize.value) || state.baseBet;
    const effective = state.baseBet * state.powerMultiplier;
    els.betSize.value = effective.toFixed(2);
    els.betSize.readOnly = true;
  } else {
    els.betSize.readOnly = false;
    els.betSize.value = Number(state.baseBet).toFixed(2);
  }
  els.betControl.classList.toggle("bet-locked", on);
  els.powerBetToggle.classList.toggle("is-active", on);
  els.betPowerHint.classList.toggle("hidden", !on);
}

/* ----------------------------------------------------------------- render */

function buildGrid() {
  els.reels.innerHTML = "";
  gridCells = Array.from({ length: ROWS }, () => new Array(COLS));
  for (let c = 0; c < COLS; c++) {
    const reel = document.createElement("div");
    reel.className = "reel";
    for (let r = 0; r < ROWS; r++) {
      const cell = document.createElement("div");
      cell.className = "cell";
      reel.appendChild(cell);
      gridCells[r][c] = cell;
    }
    els.reels.appendChild(reel);
  }
  renderMatrix(randomMatrix());
}

/** Symbol ids used to dress the idle reels — wild/scatter excluded so the resting grid looks natural. */
const FILLER_SYMBOL_IDS = Object.keys(SYMBOLS)
  .map(Number)
  .filter((id) => !/wild|scatter/i.test((SYMBOLS[id] && SYMBOLS[id].name) || ""));

function randomFillerId() {
  return FILLER_SYMBOL_IDS[Math.floor(Math.random() * FILLER_SYMBOL_IDS.length)];
}

/** A purely decorative random grid for the initial/idle reels (never evaluated). */
function randomMatrix() {
  return Array.from({ length: ROWS }, () =>
    Array.from({ length: COLS }, () => randomFillerId()));
}

function renderMatrix(matrix, winLines = []) {
  for (let r = 0; r < ROWS; r++) {
    for (let c = 0; c < COLS; c++) {
      const id = matrix[r][c];
      const meta = SYMBOLS[id] || { glyph: "?", name: id };
      const cell = gridCells[r][c];
      cell.className = `cell sym-${id}`;
      cell.innerHTML = `<span>${meta.glyph}</span><small>${meta.name}</small>`;
    }
  }
  // Highlight winning positions.
  const wins = new Set();
  for (const w of winLines) {
    const coords = PAYLINES[w.lineId];
    if (!coords) continue;
    for (let i = 0; i < w.count && i < coords.length; i++) {
      wins.add(`${coords[i][0]}:${coords[i][1]}`);
    }
  }
  for (const key of wins) {
    const [r, c] = key.split(":").map(Number);
    gridCells[r][c].classList.add("win");
  }
}

function flashSpinning() {
  for (let r = 0; r < ROWS; r++)
    for (let c = 0; c < COLS; c++) gridCells[r][c].classList.add("spinning");
  setTimeout(() => {
    for (let r = 0; r < ROWS; r++)
      for (let c = 0; c < COLS; c++) gridCells[r][c].classList.remove("spinning");
  }, 350);
}

function renderWin(totalWin, winLines = []) {
  const amount = Number(totalWin ?? 0);
  // The WIN box stays mounted in the layout at all times so it never shifts the
  // controls below it; we only toggle a dimmed "empty" state when there's no win.
  els.winAmount.textContent = fmt(amount);
  els.winBanner.classList.toggle("is-empty", amount <= 0);
  els.winLines.innerHTML = "";
  for (const w of winLines || []) {
    const chip = document.createElement("span");
    chip.className = "win-chip";
    const sym = SYMBOLS[w.symbolId];
    chip.textContent = `Line ${w.lineId}: ${sym ? sym.name : w.symbolId} ×${w.count} → ${fmt(w.payout)}`;
    els.winLines.appendChild(chip);
  }
}

function renderHud() {
  els.balance.textContent = `${fmt(state.balance)} ${CURRENCY}`;
  els.gameState.textContent = state.currentState;
}

function renderActions() {
  const actions = state.availableActions || [];
  const can = (a) => actions.includes(a);
  els.spinBtn.disabled = !can("SPIN");
  els.buyFreeSpins.disabled = !can("BUY_FEATURE");

  const needsStart = can("START_FREE_SPINS") || can("START_PICK_COLLECT");
  els.startFeature.classList.toggle("hidden", !needsStart);
  if (needsStart) {
    els.startFeature.dataset.feature = can("START_FREE_SPINS") ? "FREE_SPINS" : "PICK_COLLECT";
    els.startFeature.textContent = can("START_FREE_SPINS") ? "Start Free Spins" : "Start Pick & Collect";
  }
}

function renderPickBoard(view) {
  if (!view) {
    els.pickPanel.classList.add("hidden");
    return;
  }
  els.pickPanel.classList.remove("hidden");
  els.pickCollected.textContent = fmt(view.currentCollected);
  els.pickTotal.textContent = fmt(view.totalFeatureWin);
  els.pickRemaining.textContent = view.remainingPicks;

  const revealed = {};
  for (const p of view.revealedPicks || []) revealed[p.position] = p;

  els.pickBoard.innerHTML = "";
  const canPick = (state.availableActions || []).includes("PICK");
  for (let i = 0; i < view.boardSize; i++) {
    const tile = document.createElement("div");
    const opened = (view.openedPositions || []).includes(i);
    tile.className = "tile";
    if (opened && revealed[i]) {
      const p = revealed[i];
      tile.classList.add("opened", `t-${p.type}`);
      const val = p.value != null && Number(p.value) !== 0 ? fmt(p.value) : "";
      tile.innerHTML = `<span class="tile-val">${val}</span><span class="tile-type">${p.type}</span>`;
    } else {
      tile.textContent = i + 1;
      if (canPick) tile.addEventListener("click", () => pickTile(i));
    }
    els.pickBoard.appendChild(tile);
  }
}

/* ----------------------------------------------------------------- flows */

function applySessionView(resp) {
  if (resp.sessionId) state.sessionId = resp.sessionId;
  if (typeof resp.sessionVersion === "number") state.sessionVersion = resp.sessionVersion;
  if (resp.availableActions) state.availableActions = resp.availableActions;

  // currentState can live at top level or inside sessionState (spin).
  const newState = resp.currentState
    || (resp.sessionState && resp.sessionState.currentState)
    || (resp.enteredState);
  if (newState) state.currentState = newState;

  const fs = resp.remainingFreeSpins
    ?? (resp.sessionState && resp.sessionState.remainingFreeSpins);
  if (typeof fs === "number") els.freeSpins.textContent = fs;

  if (typeof resp.balance === "number") state.balance = resp.balance;
}

async function refreshBalance() {
  try {
    const bal = await api("/api/v1/wallet/balance");
    state.balance = Number(bal.balance);
    renderHud();
  } catch (e) {
    // non-fatal
  }
}

async function bootSession() {
  try {
    setBusy(true);
    const suffix = crypto.randomUUID().slice(0, 8);
    state.playerId = `demo-${suffix}`;
    state.sessionId = crypto.randomUUID();

    const tokenResp = await api("/api/v1/dev/token", {
      method: "POST",
      body: {
        playerId: state.playerId,
        sessionId: state.sessionId,
        currency: CURRENCY,
        roles: ["PLAYER", "ADMIN"],
        ttlMinutes: 720,
      },
    });
    state.token = tokenResp.token;

    const init = await api("/api/v1/slot/init", {
      method: "POST",
      body: { gameId: GAME_ID, currency: CURRENCY },
    });
    applySessionView(init);
    const mult = init.featureFlags && init.featureFlags.powerBetMultiplier;
    if (mult != null) state.powerMultiplier = Number(mult);
    // Only read the base bet from the field when it isn't showing the locked (multiplied) value.
    if (!els.powerBet.checked) state.baseBet = Number(els.betSize.value) || state.baseBet;
    updatePowerMultLabels();
    applyPowerBetState();
    renderHud();
    renderActions();
    renderPickBoard(init.activeFeatureView);
    logResponse("init", init);
    toast(`Demo player ${state.playerId} ready`, "success");
  } catch (e) {
    handleError("Boot failed", e);
  } finally {
    setBusy(false);
  }
}

async function doSpin() {
  try {
    setBusy(true);
    flashSpinning();
    const resp = await api("/api/v1/slot/spin", {
      method: "POST",
      idempotency: true,
      body: {
        gameId: GAME_ID,
        sessionId: state.sessionId,
        sessionVersion: state.sessionVersion,
        betSize: betForRequest(),
        powerBetActive: els.powerBet.checked,
      },
    });
    applySessionView(resp);
    renderMatrix(resp.matrix, resp.winLines);
    renderWin(resp.totalWin, resp.winLines);
    await refreshBalance();
    renderActions();
    renderPickBoard(null);
    logResponse("spin", resp);
    announceFeatures(resp.featuresTriggered);
    await maybeOfferFreeSpins();
  } catch (e) {
    handleError("Spin failed", e);
  } finally {
    setBusy(false);
  }
}

function announceFeatures(f) {
  if (!f) return;
  if (f.freeSpinsAwarded > 0) toast(`🎉 ${f.freeSpinsAwarded} free spins awarded!`, "success");
  else if (f.pickCollectTriggered) toast("🎁 Pick & Collect triggered!", "success");
}

async function buyFeature(buyType) {
  try {
    setBusy(true);
    const resp = await api("/api/v1/slot/feature/buy", {
      method: "POST",
      idempotency: true,
      body: {
        gameId: GAME_ID,
        sessionId: state.sessionId,
        sessionVersion: state.sessionVersion,
        buyType,
        betSize: betForRequest(),
      },
    });
    applySessionView(resp);
    await refreshBalance();
    renderActions();
    renderPickBoard(resp.activeFeatureView);
    logResponse("feature/buy", resp);
    toast(`Bought ${buyType} for ${fmt(resp.cost)} ${CURRENCY}`, "success");
    await maybeOfferFreeSpins();
  } catch (e) {
    handleError("Feature buy failed", e);
  } finally {
    setBusy(false);
  }
}

async function startFeature(featureType) {
  try {
    setBusy(true);
    const resp = await api("/api/v1/slot/feature/start", {
      method: "POST",
      idempotency: true,
      body: {
        gameId: GAME_ID,
        sessionId: state.sessionId,
        sessionVersion: state.sessionVersion,
        featureType,
      },
    });
    applySessionView(resp);
    await refreshBalance();
    renderActions();
    renderPickBoard(resp.activeFeatureView);
    logResponse("feature/start", resp);
  } catch (e) {
    handleError("Feature start failed", e);
  } finally {
    setBusy(false);
  }
}

/**
 * Industry-standard free-spins entry: once free spins are awarded (by buying the
 * feature or landing scatters), ask the player whether to start now. On confirm we
 * auto-play every free spin and present the cumulative win at the end.
 */
async function maybeOfferFreeSpins() {
  if (!(state.availableActions || []).includes("START_FREE_SPINS")) return;
  const spins = Number(els.freeSpins.textContent) || 0;
  const start = await showModal({
    icon: "🎉",
    title: "Free Spins Awarded!",
    message: `You have ${spins} free spin${spins === 1 ? "" : "s"} ready. Start them now?`,
    confirmLabel: "Start Free Spins",
    cancelLabel: "Later",
  });
  if (start) await runFreeSpinsAutoplay();
}

async function runFreeSpinsAutoplay() {
  try {
    setBusy(true);
    els.startFeature.classList.add("hidden");

    // 1. Enter the free-spins loop.
    const startResp = await api("/api/v1/slot/feature/start", {
      method: "POST",
      idempotency: true,
      body: {
        gameId: GAME_ID,
        sessionId: state.sessionId,
        sessionVersion: state.sessionVersion,
        featureType: "FREE_SPINS",
      },
    });
    applySessionView(startResp);
    renderPickBoard(startResp.activeFeatureView);
    logResponse("feature/start", startResp);

    // 2. Auto-play until the loop settles (server locks the bet to the trigger bet).
    let totalWin = 0;
    let spinsPlayed = 0;
    while ((state.availableActions || []).includes("SPIN")
        && state.currentState === "FREE_SPINS_LOOP") {
      flashSpinning();
      await delay(650);
      const spin = await api("/api/v1/slot/spin", {
        method: "POST",
        idempotency: true,
        body: {
          gameId: GAME_ID,
          sessionId: state.sessionId,
          sessionVersion: state.sessionVersion,
          // Free-spin bet is locked to the triggering bet server-side, and Power Bet does not
          // persist into free spins for these games — always send the base bet without power.
          betSize: betForRequest(),
          powerBetActive: false,
        },
      });
      applySessionView(spin);
      renderMatrix(spin.matrix, spin.winLines);
      renderWin(spin.totalWin, spin.winLines);
      logResponse(`free spin ${++spinsPlayed}`, spin);

      // On the settling spin the server credits and reports the whole feature win.
      if (state.currentState !== "FREE_SPINS_LOOP") {
        totalWin = Number(spin.totalWin ?? 0);
      }
    }

    await refreshBalance();
    renderActions();
    renderPickBoard(null);

    // 3. Present the cumulative result.
    await showModal({
      icon: "🏆",
      title: "Free Spins Complete",
      message: `${spinsPlayed} free spin${spinsPlayed === 1 ? "" : "s"} played · `
        + `Total win ${fmt(totalWin)} ${CURRENCY}`,
      confirmLabel: "Collect",
    });
    renderWin(totalWin);
    toast(`Free spins won ${fmt(totalWin)} ${CURRENCY}`, "success");
  } catch (e) {
    handleError("Free spins failed", e);
  } finally {
    setBusy(false);
  }
}

async function pickTile(position) {
  try {
    setBusy(true);
    const resp = await api("/api/v1/slot/feature/pick", {
      method: "POST",
      idempotency: true,
      body: {
        gameId: GAME_ID,
        sessionId: state.sessionId,
        sessionVersion: state.sessionVersion,
        position,
      },
    });
    applySessionView(resp);
    renderPickBoard(resp.activeFeatureView);
    renderActions();
    logResponse("feature/pick", resp);
    if (resp.featureCompleted) {
      await refreshBalance();
      toast(`Feature complete! Won ${fmt(resp.featureTotalWin)} ${CURRENCY}`, "success");
    }
  } catch (e) {
    handleError("Pick failed", e);
  } finally {
    setBusy(false);
  }
}

async function runSimulation() {
  try {
    els.runSim.disabled = true;
    els.simReport.innerHTML = "<em>Running…</em>";
    const resp = await api("/api/v1/admin/simulator/run", {
      method: "POST",
      body: {
        gameId: GAME_ID,
        mathVersion: "v1",
        bet: Number(els.simBet.value),
        spinsBaseGame: Number(els.simBase.value),
        spinsPowerBet: Number(els.simPower.value),
        spinsBonusBuyFreeSpins: Number(els.simFs.value),
        pickStrategy: els.simStrategy.value,
      },
    });
    renderSimReport(resp);
    logResponse("simulator/run", resp);
  } catch (e) {
    els.simReport.innerHTML = "";
    handleError("Simulation failed", e);
  } finally {
    els.runSim.disabled = false;
  }
}

function renderSimReport(report) {
  const channels = report.channels || {};
  const row = (name, ch, strong = false) => {
    if (!ch) return "";
    const label = strong ? `<strong>${name}</strong>` : name;
    return `
    <tr${strong ? ' class="row-overall"' : ""}>
      <td>${label}</td>
      <td>${Number(ch.spins || 0).toLocaleString()}</td>
      <td>${fmt(ch.totalBet)}</td>
      <td>${fmt(ch.totalWin)}</td>
      <td class="rtp">${fmt(ch.rtpPercent)}%</td>
    </tr>`;
  };

  // Skip channels that weren't sampled (0 spins) so the table only shows what was run.
  const rows = Object.entries(channels)
    .filter(([, ch]) => Number(ch.spins) > 0)
    .map(([name, ch]) => row(name, ch))
    .join("");

  els.simReport.innerHTML = `
    <table>
      <thead><tr><th>Channel</th><th>Spins</th><th>Total Bet</th><th>Total Win</th><th>RTP</th></tr></thead>
      <tbody>
        ${rows}
        ${row("Overall", report.overall, true)}
      </tbody>
    </table>
    <p style="color:var(--text-dim);margin-top:8px">
      ${report.elapsedMillis} ms · FS triggers: ${report.freeSpinTriggers ?? 0} · pick entries: ${report.pickEntries ?? 0}
    </p>`;
}

async function setBalance() {
  try {
    const resp = await api("/api/v1/admin/wallet/balance", {
      method: "POST",
      body: {
        playerId: state.playerId,
        currency: CURRENCY,
        balance: Number(els.adminBalance.value),
      },
    });
    state.balance = Number(resp.balance);
    renderHud();
    logResponse("admin/wallet/balance", resp);
    toast(`Balance set to ${fmt(resp.balance)} ${CURRENCY}`, "success");
  } catch (e) {
    handleError("Set balance failed", e);
  }
}

function handleError(label, e) {
  const detail = e instanceof ApiError && e.payload ? e.payload : { message: e.message };
  logResponse(label + " (ERROR)", detail);
  toast(`${label}: ${detail.message || e.message}`, "error");
}

/* ----------------------------------------------------------------- wiring */

function bindEvents() {
  els.spinBtn.addEventListener("click", doSpin);
  els.powerBet.addEventListener("change", applyPowerBetState);
  // Keep the remembered base bet in sync whenever the user edits the (unlocked) field.
  els.betSize.addEventListener("input", () => {
    if (!els.powerBet.checked) state.baseBet = Number(els.betSize.value) || state.baseBet;
  });
  els.buyFreeSpins.addEventListener("click", () => buyFeature("FREE_SPINS_BUY"));
  els.startFeature.addEventListener("click", () => {
    const feature = els.startFeature.dataset.feature || "FREE_SPINS";
    // Free spins auto-play; Pick & Collect still enters its interactive board.
    if (feature === "FREE_SPINS") runFreeSpinsAutoplay();
    else startFeature(feature);
  });
  els.resetSession.addEventListener("click", bootSession);
  els.runSim.addEventListener("click", runSimulation);
  els.setBalanceBtn.addEventListener("click", setBalance);
  els.clearLog.addEventListener("click", () => (els.log.textContent = ""));
}

/** Apply the active game's theme + branding to the page chrome. */
function applyGameChrome() {
  document.body.dataset.game = GAME_ID;
  document.title = `Velocity RGS — ${META.name}`;
  if (els.brandLogo) els.brandLogo.textContent = META.logo;
  if (els.gameName) els.gameName.textContent = META.name;
  if (els.gameTagline) els.gameTagline.textContent = `${META.tagline} · ${META.volatility} volatility`;
}

/** Pull the live buy-cost multipliers from the catalog so the labels match the math. */
async function applyGameInfo() {
  try {
    const games = await api("/api/v1/games");
    const info = (games || []).find((g) => g.gameId === GAME_ID);
    if (!info) return;
    if (info.freeSpinsBuyCostMultiplier != null) {
      els.buyFreeSpinsCost.textContent = `(×${Number(info.freeSpinsBuyCostMultiplier)})`;
    }
  } catch (e) {
    // non-fatal — labels just keep their placeholder
  }
}

function main() {
  applyGameChrome();
  buildGrid();
  bindEvents();
  applyGameInfo();
  bootSession();
}

document.addEventListener("DOMContentLoaded", main);
