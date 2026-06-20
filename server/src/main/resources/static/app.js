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
  powerBet: $("powerBet"),
  spinBtn: $("spinBtn"),
  buyFreeSpins: $("buyFreeSpins"),
  buyPickCollect: $("buyPickCollect"),
  startFeature: $("startFeature"),
  pickPanel: $("pickPanel"),
  pickBoard: $("pickBoard"),
  pickCollected: $("pickCollected"),
  pickTotal: $("pickTotal"),
  pickRemaining: $("pickRemaining"),
  resetSession: $("resetSession"),
  simBet: $("simBet"),
  simBase: $("simBase"),
  simFs: $("simFs"),
  simPc: $("simPc"),
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
  buyPickCollectCost: $("buyPickCollectCost"),
};

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
  els.spinBtn.disabled = busy;
  els.buyFreeSpins.disabled = busy;
  els.buyPickCollect.disabled = busy;
  els.startFeature.disabled = busy;
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
  renderMatrix(emptyMatrix());
}

function emptyMatrix() {
  return Array.from({ length: ROWS }, () => new Array(COLS).fill(1));
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
  if (amount > 0) {
    els.winAmount.textContent = fmt(amount);
    els.winBanner.classList.remove("hidden");
  } else {
    els.winBanner.classList.add("hidden");
  }
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
  els.buyPickCollect.disabled = !can("BUY_FEATURE");

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
        betSize: Number(els.betSize.value),
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
        betSize: Number(els.betSize.value),
      },
    });
    applySessionView(resp);
    await refreshBalance();
    renderActions();
    renderPickBoard(resp.activeFeatureView);
    logResponse("feature/buy", resp);
    toast(`Bought ${buyType} for ${fmt(resp.cost)} ${CURRENCY}`, "success");
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
        spinsBonusBuyFreeSpins: Number(els.simFs.value),
        spinsBonusBuyPickCollect: Number(els.simPc.value),
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
  const rows = Object.entries(channels).map(([name, ch]) => `
    <tr>
      <td>${name}</td>
      <td>${Number(ch.spins).toLocaleString()}</td>
      <td class="rtp">${fmt(ch.rtpPercent)}%</td>
    </tr>`).join("");

  els.simReport.innerHTML = `
    <table>
      <thead><tr><th>Channel</th><th>Spins</th><th>RTP</th></tr></thead>
      <tbody>
        ${rows}
        <tr>
          <td><strong>Overall</strong></td>
          <td>${Number(report.overall?.spins || 0).toLocaleString()}</td>
          <td class="rtp">${fmt(report.overall?.rtpPercent)}%</td>
        </tr>
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
  els.buyFreeSpins.addEventListener("click", () => buyFeature("FREE_SPINS_BUY"));
  els.buyPickCollect.addEventListener("click", () => buyFeature("PICK_COLLECT_BUY"));
  els.startFeature.addEventListener("click", () =>
    startFeature(els.startFeature.dataset.feature || "FREE_SPINS"));
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
    if (info.pickCollectBuyCostMultiplier != null) {
      els.buyPickCollectCost.textContent = `(×${Number(info.pickCollectBuyCostMultiplier)})`;
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
