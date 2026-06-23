"use strict";

/* =========================================================================
 * Velocity RGS — Demo Test Harness (vanilla JS)
 * Talks directly to the Spring Boot demo backend on the same origin.
 * Auto-mints a demo JWT (PLAYER + ADMIN) so no manual login is required.
 * ======================================================================= */

const CURRENCY = "EUR";

/**
 * The active game's config, fetched from the backend catalog (see games.js) during init. Everything the
 * client needs to draw and play the game — presentation, grid shape, paylines and symbol glyphs — is
 * server-driven, so introducing a different layout (3x3, 5x4, more/fewer paylines …) or a brand-new game
 * needs no changes here.
 */
let GAME_ID = new URLSearchParams(window.location.search).get("game") || "";
let META = null;     // the resolved game summary from /api/v1/games
let SYMBOLS = {};     // { symbolId: { glyph, name } }
let PAYLINES = {};    // { lineId: [[row, col], …] } used for win highlighting
let ROWS = 0;
let COLS = 0;
// How long the reels visibly roll before settling — server-driven per game (catalog), with a safe default.
let SPIN_MS = 600;
// The discrete stakes a player may wager — fully server-driven (catalog betValues). The bet slider steps
// through these by index; DEFAULT_BET seeds the initial selection. The server re-validates every spin, so
// these are only a convenience for the UI.
let BET_VALUES = [];
let DEFAULT_BET = 1.0;
// Bet-selector component instances (see createBetSlider) — one for the main game, one for the simulator.
let mainBetSlider = null;
let simBetSlider = null;

/**
 * Load the active game's config from the backend catalog and populate the module state above. Resolves the
 * game from the ?game= query param, falling back to the first registered game.
 */
async function loadGameConfig() {
  const catalog = await fetchCatalog();
  const game = resolveGame(catalog, GAME_ID);
  if (!game) throw new Error("No games are registered on the server");
  META = game;
  GAME_ID = game.gameId;
  SYMBOLS = buildSymbolMap(game);
  PAYLINES = buildPaylineMap(game);
  ROWS = game.rows;
  COLS = game.cols;
  if (Number(game.spinDurationMillis) > 0) SPIN_MS = Number(game.spinDurationMillis);
  BET_VALUES = (game.betValues || []).map(Number).filter((v) => v > 0).sort((a, b) => a - b);
  DEFAULT_BET = Number(game.defaultBet) || BET_VALUES[0] || 1.0;
  state.baseBet = DEFAULT_BET;
  // Filler symbols (wild/scatter excluded) used to dress the idle reels — derived from the live symbol set.
  FILLER_SYMBOL_IDS = Object.keys(SYMBOLS)
    .map(Number)
    .filter((id) => !/wild|scatter/i.test((SYMBOLS[id] && SYMBOLS[id].name) || ""));
}

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
  betSlider: $("betSlider"),
  betValue: $("betValue"),
  betMin: $("betMin"),
  betMax: $("betMax"),
  betControl: $("betControl"),
  betPowerHint: $("betPowerHint"),
  betPowerMult: $("betPowerMult"),
  betEffective: $("betEffective"),
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
  simSlider: $("simSlider"),
  simBetValue: $("simBetValue"),
  simBetMin: $("simBetMin"),
  simBetMax: $("simBetMax"),
  simBase: $("simBase"),
  simPower: $("simPower"),
  simFs: $("simFs"),
  simStrategy: $("simStrategy"),
  runSim: $("runSim"),
  simReport: $("simReport"),
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
  showInfo: $("showInfo"),
  infoModal: $("infoModal"),
  infoClose: $("infoClose"),
  infoLogo: $("infoLogo"),
  infoTitle: $("infoTitle"),
  infoTagline: $("infoTagline"),
  infoStats: $("infoStats"),
  infoParagraphs: $("infoParagraphs"),
  infoSpecs: $("infoSpecs"),
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

/**
 * The last "primary" request we made — captured here so the log panel can show the request that produced
 * a response (see logResponse). Background/polling calls pass `track: false` so they don't clobber it
 * (e.g. the wallet-balance refresh fired right after a spin).
 */
let lastApiRequest = null;

async function api(path, { method = "GET", body, idempotency = false, track = true } = {}) {
  const headers = { "Content-Type": "application/json" };
  if (state.token) headers["Authorization"] = "Bearer " + state.token;
  if (idempotency) headers["Idempotency-Key"] = crypto.randomUUID();

  if (track) lastApiRequest = { method, path, body: body ?? null };

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
  const req = lastApiRequest;
  const requestBlock = req
    ? `// request — ${req.method} ${req.path}\n` +
      (req.body != null ? JSON.stringify(req.body, null, 2) : "(no body)")
    : "// request\n(none)";
  els.log.textContent =
    `// ${label}  @ ${new Date().toLocaleTimeString()}\n\n` +
    requestBlock +
    `\n\n// response\n` +
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
    els.startFeature.disabled = true;
    return;
  }
  // When clearing the busy state, re-derive enablement from the legal actions
  // instead of blanket-enabling — otherwise SPIN becomes clickable in states like
  // FREE_SPINS_AWAITING where only START_FREE_SPINS is allowed.
  els.startFeature.disabled = false;
  renderActions();
}

/* ----------------------------------------------------------------- bet slider */

/** Nearest index into BET_VALUES for a stake — used to seed a slider from the default/session bet. */
function betIndexFor(value) {
  if (!BET_VALUES.length) return 0;
  let best = 0;
  for (let i = 1; i < BET_VALUES.length; i++) {
    if (Math.abs(BET_VALUES[i] - value) < Math.abs(BET_VALUES[best] - value)) best = i;
  }
  return best;
}

/**
 * Reusable bet-selector component shared by the main game and the RTP simulator. Binds a range input so it
 * steps through the server-driven BET_VALUES by index, mirrors the chosen stake into a readout (and optional
 * min/max scale), and notifies `onChange`. Returns `{ value, setValue() }` — `value` is the live stake.
 */
function createBetSlider({ slider, valueEl, minEl, maxEl, initial, onChange }) {
  const max = Math.max(0, BET_VALUES.length - 1);
  slider.min = "0";
  slider.max = String(max);
  slider.step = "1";
  slider.disabled = BET_VALUES.length <= 1;
  if (BET_VALUES.length) {
    if (minEl) minEl.textContent = fmt(BET_VALUES[0]);
    if (maxEl) maxEl.textContent = fmt(BET_VALUES[max]);
  }

  const component = {
    get value() {
      if (!BET_VALUES.length) return Number(initial) || 0;
      const idx = Math.min(BET_VALUES.length - 1, Math.max(0, Number(slider.value) | 0));
      return BET_VALUES[idx];
    },
    setValue(stake) {
      slider.value = String(betIndexFor(stake));
      refresh();
    },
  };

  function refresh() {
    if (valueEl) valueEl.textContent = fmt(component.value);
    if (onChange) onChange(component.value);
  }

  slider.addEventListener("input", refresh);
  component.setValue(initial);
  return component;
}

/* -------------------------------------------------------------- power bet */

/** The per-spin base stake to send to the server — always one of the configured bet values; server ×N. */
function betForRequest() {
  return state.baseBet;
}

/** Push the live multiplier (from the server) into the on-screen labels. */
function updatePowerMultLabels() {
  const m = state.powerMultiplier;
  if (els.powerBetMultLabel) els.powerBetMultLabel.textContent = m;
  if (els.betPowerMult) els.betPowerMult.textContent = m;
}

/** Show the multiplied effective stake while Power Bet is on (the base stake shows in the slider readout). */
function updateBetEffective() {
  const on = els.powerBet.checked;
  if (els.betEffective) els.betEffective.textContent = fmt(state.baseBet * (on ? state.powerMultiplier : 1));
}

/**
 * Reflect the Power Bet toggle. The slider always selects the base stake (sent to the server); when Power
 * Bet is on we surface the multiplied effective stake in the readout instead of locking the control.
 */
function applyPowerBetState() {
  const on = els.powerBet.checked;
  els.betControl.classList.toggle("bet-locked", on);
  els.powerBetToggle.classList.toggle("is-active", on);
  els.betPowerHint.classList.toggle("hidden", !on);
  updateBetEffective();
}

/** Build the main-game and simulator bet selectors from the shared slider component (server-driven values). */
function setupBetSliders() {
  mainBetSlider = createBetSlider({
    slider: els.betSlider,
    valueEl: els.betValue,
    minEl: els.betMin,
    maxEl: els.betMax,
    initial: state.baseBet,
    onChange: (stake) => {
      state.baseBet = stake;
      updateBetEffective();
    },
  });
  simBetSlider = createBetSlider({
    slider: els.simSlider,
    valueEl: els.simBetValue,
    minEl: els.simBetMin,
    maxEl: els.simBetMax,
    initial: DEFAULT_BET,
  });
}

/* ----------------------------------------------------------------- render */

function buildGrid() {
  els.reels.innerHTML = "";
  // The grid shape is server-driven (catalog rows/cols), so the reel layout is sized from it rather
  // than a fixed 5×3 — a 4-reel (Frost) or 6-reel (Inferno) board renders with no other changes.
  els.reels.style.gridTemplateColumns = `repeat(${COLS}, 1fr)`;
  gridCells = Array.from({ length: ROWS }, () => new Array(COLS));
  for (let c = 0; c < COLS; c++) {
    const reel = document.createElement("div");
    reel.className = "reel";
    reel.style.gridTemplateRows = `repeat(${ROWS}, 1fr)`;
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
let FILLER_SYMBOL_IDS = [];

function randomFillerId() {
  return FILLER_SYMBOL_IDS[Math.floor(Math.random() * FILLER_SYMBOL_IDS.length)];
}

/** A purely decorative random grid for the initial/idle reels (never evaluated). */
function randomMatrix() {
  return Array.from({ length: ROWS }, () =>
    Array.from({ length: COLS }, () => randomFillerId()));
}

/** Inner markup for a single symbol cell — shared by the resting grid and the spin strip. */
function symbolCellHTML(id) {
  const meta = SYMBOLS[id] || { glyph: "?", name: id };
  return `<div class="cell sym-${id}"><span>${meta.glyph}</span><small>${meta.name}</small></div>`;
}

/** Paint one resting grid cell with the given symbol (clears any prior win state). */
function paintCell(r, c, id) {
  const meta = SYMBOLS[id] || { glyph: "?", name: id };
  const cell = gridCells[r][c];
  cell.className = `cell sym-${id}`;
  cell.innerHTML = `<span>${meta.glyph}</span><small>${meta.name}</small>`;
}

/** Toggle the gold win highlight on the positions covered by the given win lines. */
function applyWins(winLines = []) {
  for (let r = 0; r < ROWS; r++)
    for (let c = 0; c < COLS; c++) gridCells[r][c].classList.remove("win");
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

function renderMatrix(matrix, winLines = []) {
  for (let r = 0; r < ROWS; r++)
    for (let c = 0; c < COLS; c++) paintCell(r, c, matrix[r][c]);
  applyWins(winLines);
}

/* ----------------------------------------------------------------- spin animation */

/** Lay a scrolling, motion-blurred symbol strip over every reel to fake the spin. */
function startSpin() {
  applyWins([]); // clear any prior win highlight before the reels move
  for (let c = 0; c < COLS; c++) {
    const reel = els.reels.children[c];
    reel.classList.remove("landed");
    reel.classList.add("spinning");
    const strip = document.createElement("div");
    strip.className = "reel-strip";
    // A handful of random fillers, duplicated so translateY(-50%) loops seamlessly.
    const ids = Array.from({ length: ROWS + 4 }, randomFillerId);
    const html = ids.map(symbolCellHTML).join("");
    strip.innerHTML = html + html;
    reel.appendChild(strip);
  }
}

/** Tear down all spin strips immediately (used on error). */
function stopSpin() {
  for (let c = 0; c < COLS; c++) {
    const reel = els.reels.children[c];
    reel.classList.remove("spinning", "landed");
    const strip = reel.querySelector(".reel-strip");
    if (strip) strip.remove();
  }
}

/** Stop the reels left→right, dropping each column onto its final symbols with a bounce. */
async function settleReels(matrix, winLines = []) {
  for (let c = 0; c < COLS; c++) {
    await delay(110);
    for (let r = 0; r < ROWS; r++) paintCell(r, c, matrix[r][c]);
    const reel = els.reels.children[c];
    const strip = reel.querySelector(".reel-strip");
    if (strip) strip.remove();
    reel.classList.remove("spinning");
    reel.classList.add("landed");
  }
  applyWins(winLines);
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
    const bal = await api("/api/v1/wallet/balance", { track: false });
    state.balance = Number(bal.balance);
    renderHud();
  } catch (e) {
    // non-fatal
  }
}

/**
 * The demo player id is persisted in localStorage so reloading the page resumes the same player —
 * keeping the balance and building a meaningful round History. "New Player" passes forceNew to mint
 * a brand-new id (and a fresh session/balance). The History page reads the same key.
 */
const PLAYER_KEY = "velocity.playerId";

function resolvePlayerId(forceNew) {
  let id = forceNew ? null : localStorage.getItem(PLAYER_KEY);
  if (!id) {
    id = `demo-${crypto.randomUUID().slice(0, 8)}`;
    localStorage.setItem(PLAYER_KEY, id);
  }
  return id;
}

async function bootSession(forceNewPlayer = false) {
  try {
    setBusy(true);
    state.playerId = resolvePlayerId(forceNewPlayer);
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
    // Seed the slider from the resumed session's stake when it's one of the configured bet values;
    // otherwise keep the catalog default already set during loadGameConfig.
    if (init.currentBet != null && BET_VALUES.some((v) => Math.abs(v - Number(init.currentBet)) < 1e-9)) {
      mainBetSlider.setValue(Number(init.currentBet));
    }
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
    startSpin();
    // Spin for at least a beat even if the server replies instantly, then stop the reels.
    const [resp] = await Promise.all([
      api("/api/v1/slot/spin", {
        method: "POST",
        idempotency: true,
        body: {
          gameId: GAME_ID,
          sessionId: state.sessionId,
          sessionVersion: state.sessionVersion,
          betSize: betForRequest(),
          powerBetActive: els.powerBet.checked,
        },
      }),
      delay(SPIN_MS),
    ]);
    applySessionView(resp);
    await settleReels(resp.matrix, resp.winLines);
    renderWin(resp.totalWin, resp.winLines);
    await refreshBalance();
    renderActions();
    renderPickBoard(null);
    logResponse("spin", resp);
    announceFeatures(resp.featuresTriggered);
    await maybeOfferFreeSpins();
  } catch (e) {
    stopSpin();
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
      startSpin();
      const [spin] = await Promise.all([
        api("/api/v1/slot/spin", {
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
        }),
        delay(SPIN_MS),
      ]);
      applySessionView(spin);
      await settleReels(spin.matrix, spin.winLines);
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
    stopSpin();
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
        bet: simBetSlider.value,
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

function handleError(label, e) {
  const detail = e instanceof ApiError && e.payload ? e.payload : { message: e.message };
  logResponse(label + " (ERROR)", detail);
  toast(`${label}: ${detail.message || e.message}`, "error");
}

/* ----------------------------------------------------------------- wiring */

function bindEvents() {
  els.spinBtn.addEventListener("click", doSpin);
  els.powerBet.addEventListener("change", applyPowerBetState);
  els.buyFreeSpins.addEventListener("click", () => buyFeature("FREE_SPINS_BUY"));
  els.startFeature.addEventListener("click", () => {
    const feature = els.startFeature.dataset.feature || "FREE_SPINS";
    // Free spins auto-play; Pick & Collect still enters its interactive board.
    if (feature === "FREE_SPINS") runFreeSpinsAutoplay();
    else startFeature(feature);
  });
  els.resetSession.addEventListener("click", () => bootSession(true));
  els.runSim.addEventListener("click", runSimulation);
  els.clearLog.addEventListener("click", () => (els.log.textContent = ""));
  els.showInfo.addEventListener("click", openInfoModal);
  els.infoClose.addEventListener("click", closeInfoModal);
  // Click the dim backdrop (but not the card) to dismiss.
  els.infoModal.addEventListener("click", (e) => {
    if (e.target === els.infoModal) closeInfoModal();
  });
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && !els.infoModal.classList.contains("hidden")) closeInfoModal();
  });
}

/** Apply the active game's theme + branding to the page chrome. */
function applyGameChrome() {
  document.body.dataset.game = GAME_ID;
  document.title = `Velocity RGS — ${META.title}`;
  if (els.brandLogo) els.brandLogo.textContent = META.logo;
  if (els.gameName) els.gameName.textContent = META.title;
  if (els.gameTagline) els.gameTagline.textContent = `${META.tagline} · ${META.volatility} volatility`;
}

/** Reflect the live free-spins buy-cost multiplier from the catalog so the label matches the math. */
function applyGameInfo() {
  if (META && META.freeSpinsBuyCostMultiplier != null) {
    els.buyFreeSpinsCost.textContent = `(×${Number(META.freeSpinsBuyCostMultiplier)})`;
  }
  buildInfoModal();
}

/**
 * Populate the "Show Game Info" modal entirely from the server-driven catalog (META.info). Everything —
 * marketing copy, the stat cards and the spec sheet — is authored in the game JSON, so this only lays the
 * provided strings into the DOM. If a game ships no info block, the button is hidden.
 */
function buildInfoModal() {
  const info = META && META.info;
  const hasInfo =
    info && ((info.paragraphs && info.paragraphs.length) ||
             (info.stats && info.stats.length) ||
             (info.specs && info.specs.length));
  if (els.showInfo) els.showInfo.classList.toggle("hidden", !hasInfo);
  if (!hasInfo) return;

  els.infoLogo.textContent = META.logo || "🎰";
  els.infoTitle.textContent = META.title || "";
  els.infoTagline.textContent = META.tagline || "";

  els.infoStats.replaceChildren(
    ...(info.stats || []).map((s) => {
      const card = document.createElement("div");
      card.className = "info-stat";
      const label = document.createElement("span");
      label.className = "info-stat-label";
      label.textContent = s.label;
      const value = document.createElement("strong");
      value.className = "info-stat-value";
      value.textContent = s.value;
      card.append(label, value);
      return card;
    })
  );

  els.infoParagraphs.replaceChildren(
    ...(info.paragraphs || []).map((text) => {
      const p = document.createElement("p");
      p.textContent = text;
      return p;
    })
  );

  els.infoSpecs.replaceChildren(
    ...(info.specs || []).map((spec) => {
      const row = document.createElement("div");
      row.className = "info-spec";
      const label = document.createElement("span");
      label.className = "info-spec-label";
      label.textContent = spec.label;
      const values = document.createElement("span");
      values.className = "info-spec-values";
      for (const v of spec.values || []) {
        const line = document.createElement("span");
        line.className = "info-spec-line";
        line.textContent = v;
        values.appendChild(line);
      }
      row.append(label, values);
      return row;
    })
  );
}

function openInfoModal() {
  els.infoModal.classList.remove("hidden");
}

function closeInfoModal() {
  els.infoModal.classList.add("hidden");
}

async function main() {
  bindEvents();
  try {
    await loadGameConfig();
  } catch (e) {
    toast(`Could not load game config: ${e.message}`, "error");
    return;
  }
  applyGameChrome();
  applyGameInfo();
  setupBetSliders();
  buildGrid();
  bootSession();
}

document.addEventListener("DOMContentLoaded", main);
