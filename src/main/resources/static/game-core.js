"use strict";

/* =========================================================================
 * Velocity RGS - shared game-page core.
 *
 * Cross-game plumbing used by BOTH game clients (slot.js and roulette.js): the
 * API helper + dev-token mint, demo player-id persistence, toast, the shared
 * confirm/summary modal, the request/response log, the fully data-driven "Show
 * Game Info" modal, page chrome/theming, and formatting. Each game module only
 * implements its own play surface (reels vs. wheel/table) on top of this.
 *
 * The active auth token lives on window.__authToken so api() can attach it
 * regardless of which game module is driving the page.
 * ======================================================================= */

const CURRENCY = "EUR";

/* ----------------------------------------------------------------- API */

class ApiError extends Error {
  constructor(status, payload) {
    super(payload && payload.message ? payload.message : `HTTP ${status}`);
    this.status = status;
    this.payload = payload;
  }
}

/** Last "primary" request, captured so the log panel can show what produced a response. */
let lastApiRequest = null;

async function api(path, { method = "GET", body, idempotency = false, track = true, token } = {}) {
  const headers = { "Content-Type": "application/json" };
  const authToken = token !== undefined ? token : window.__authToken;
  if (authToken) headers["Authorization"] = "Bearer " + authToken;
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

/* ----------------------------------------------------------------- formatting */

const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function fmt(value) {
  const n = Number(value ?? 0);
  return n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

/* ----------------------------------------------------------------- toast / log */

let _toastTimer = null;
function toast(message, kind = "") {
  const el = document.getElementById("toast");
  if (!el) return;
  el.textContent = message;
  el.className = "toast " + kind;
  clearTimeout(_toastTimer);
  _toastTimer = setTimeout(() => el.classList.add("hidden"), 3200);
}

function logResponse(label, data) {
  const el = document.getElementById("log");
  if (!el) return;
  const req = lastApiRequest;
  const requestBlock = req
    ? `// request - ${req.method} ${req.path}\n` +
      (req.body != null ? JSON.stringify(req.body, null, 2) : "(no body)")
    : "// request\n(none)";
  el.textContent =
    `// ${label}  @ ${new Date().toLocaleTimeString()}\n\n` +
    requestBlock +
    `\n\n// response\n` +
    JSON.stringify(data, null, 2);
}

function handleError(label, e) {
  const detail = e instanceof ApiError && e.payload ? e.payload : { message: e.message };
  logResponse(label + " (ERROR)", detail);
  toast(`${label}: ${detail.message || e.message}`, "error");
}

/* ----------------------------------------------------------------- modal */

/**
 * Show the shared centered modal and resolve to true (confirm) / false (cancel).
 * The cancel button is hidden unless a `cancelLabel` is supplied.
 */
function showModal({ icon = "🎁", title, message, confirmLabel = "OK", cancelLabel }) {
  return new Promise((resolve) => {
    const modal = document.getElementById("fsModal");
    document.getElementById("fsModalIcon").textContent = icon;
    document.getElementById("fsModalTitle").textContent = title;
    document.getElementById("fsModalMessage").textContent = message;
    const confirm = document.getElementById("fsModalConfirm");
    const cancel = document.getElementById("fsModalCancel");
    confirm.textContent = confirmLabel;
    if (cancelLabel) {
      cancel.textContent = cancelLabel;
      cancel.classList.remove("hidden");
    } else {
      cancel.classList.add("hidden");
    }
    modal.classList.remove("hidden");
    const close = (value) => {
      modal.classList.add("hidden");
      confirm.onclick = null;
      cancel.onclick = null;
      resolve(value);
    };
    confirm.onclick = () => close(true);
    cancel.onclick = () => close(false);
  });
}

/* ----------------------------------------------------------------- demo player / token */

const PLAYER_KEY = "velocity.playerId";

/**
 * The demo player id is persisted in localStorage so reloading resumes the same player (balance +
 * round history survive). `forceNew` mints a brand-new id ("New Player"). The History page reads the
 * same key.
 */
function resolvePlayerId(forceNew) {
  let id = forceNew ? null : localStorage.getItem(PLAYER_KEY);
  if (!id) {
    id = `demo-${crypto.randomUUID().slice(0, 8)}`;
    localStorage.setItem(PLAYER_KEY, id);
  }
  return id;
}

/** Mint a demo JWT (PLAYER + ADMIN) so the embedded UI needs no manual login. */
async function mintDevToken({ playerId, sessionId, currency = CURRENCY,
                             roles = ["PLAYER", "ADMIN"], ttlMinutes = 720 }) {
  const resp = await api("/api/v1/dev/token", {
    method: "POST",
    token: null,
    body: { playerId, sessionId, currency, roles, ttlMinutes },
  });
  return resp.token;
}

/* ----------------------------------------------------------------- page chrome */

/** Apply the active game's theme + branding to the page chrome (shared by both game types). */
function applyGameChrome(game) {
  document.body.dataset.game = game.gameId;
  document.body.dataset.theme = game.theme || "";
  document.body.dataset.gameType = game.gameType || "SLOT";
  document.title = `Velocity RGS - ${game.title}`;
  const set = (id, text) => { const el = document.getElementById(id); if (el) el.textContent = text; };
  set("brandLogo", game.logo);
  set("gameName", game.title);
  set("gameTagline", `${game.tagline} · ${game.volatility} volatility`);
}

/* ----------------------------------------------------------------- info modal (data-driven) */

/**
 * Populate + wire the "Show Game Info" modal entirely from the server-driven catalog (game.info).
 * Everything - marketing copy, the stat cards and the spec sheet - is authored in the game JSON, so
 * this only lays the provided strings into the DOM. If a game ships no info block, the button is hidden.
 */
function initInfoModal(game) {
  const info = game && game.info;
  const features = (game && game.features) || [];
  const showBtn = document.getElementById("showInfo");
  const hasInfo =
    (info && ((info.paragraphs && info.paragraphs.length) ||
              (info.stats && info.stats.length) ||
              (info.specs && info.specs.length))) ||
    features.length > 0;
  if (showBtn) showBtn.classList.toggle("hidden", !hasInfo);
  if (!hasInfo) return;

  document.getElementById("infoLogo").textContent = game.logo || "🎰";
  document.getElementById("infoTitle").textContent = game.title || "";
  document.getElementById("infoTagline").textContent = game.tagline || "";

  // A game may now ship derived features with no authored `info` block at all, so read through a
  // blank object rather than assuming the block is there.
  const inf = info || {};

  // The mechanics, server-derived from the math config - same cards the lobby's info sheet shows.
  document.getElementById("infoFeatures").innerHTML = renderFeatureCards(features);

  document.getElementById("infoStats").replaceChildren(
    ...(inf.stats || []).map((s) => {
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

  document.getElementById("infoParagraphs").replaceChildren(
    ...(inf.paragraphs || []).map((text) => {
      const p = document.createElement("p");
      p.textContent = text;
      return p;
    })
  );

  document.getElementById("infoSpecs").replaceChildren(
    ...(inf.specs || []).map((spec) => {
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

  const modal = document.getElementById("infoModal");
  const open = () => modal.classList.remove("hidden");
  const close = () => modal.classList.add("hidden");
  if (showBtn) showBtn.onclick = open;
  document.getElementById("infoClose").onclick = close;
  modal.onclick = (e) => { if (e.target === modal) close(); };
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && !modal.classList.contains("hidden")) close();
  });
}

/* ----------------------------------------------------------------- bet slider component */

/** Nearest index into a stake list for a value - used to seed a slider from the default/session bet. */
function betIndexFor(values, value) {
  if (!values.length) return 0;
  let best = 0;
  for (let i = 1; i < values.length; i++) {
    if (Math.abs(values[i] - value) < Math.abs(values[best] - value)) best = i;
  }
  return best;
}

/**
 * Reusable bet-selector: binds a range input so it steps through `values` by index, mirrors the chosen
 * stake into a readout (and optional min/max scale), and notifies `onChange`. Returns `{ value, setValue }`.
 */
function createBetSlider({ slider, valueEl, minEl, maxEl, values, initial, onChange }) {
  const max = Math.max(0, values.length - 1);
  slider.min = "0";
  slider.max = String(max);
  slider.step = "1";
  slider.disabled = values.length <= 1;
  if (values.length) {
    if (minEl) minEl.textContent = fmt(values[0]);
    if (maxEl) maxEl.textContent = fmt(values[max]);
  }

  const component = {
    get value() {
      if (!values.length) return Number(initial) || 0;
      const idx = Math.min(values.length - 1, Math.max(0, Number(slider.value) | 0));
      return values[idx];
    },
    setValue(stake) {
      slider.value = String(betIndexFor(values, stake));
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
