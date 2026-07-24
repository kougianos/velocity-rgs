"use strict";

/* =========================================================================
 * Velocity RGS - Responsible Gaming panel (§4.2).
 *
 * Half the value of a limit is that the player can see it. This page sets every
 * limit in the ruleset, shows live consumption against each one, and offers the
 * two ways out - a timed break and self-exclusion - behind a confirmation.
 *
 * Every number here is server-derived: the page computes no limit, no remaining
 * balance and no verdict. It renders GET /api/v1/rg/status, which is the same
 * state the policy check reads inside the money transaction. A client that
 * disagreed with it would simply be refused by the server.
 * ======================================================================= */

const CURRENCY = "EUR";
const PLAYER_KEY = "velocity.playerId";
const API = "/api/v1/rg";

const root = document.getElementById("rgRoot");
const toastEl = document.getElementById("toast");

let token = null;
let state = null;

/* ------------------------------------------------------------------ util */

let toastTimer = null;
function toast(message, kind = "") {
  toastEl.textContent = message;
  toastEl.className = "toast " + kind;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => toastEl.classList.add("hidden"), 3200);
}

const esc = (s) => String(s ?? "").replace(/[&<>"']/g,
  (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

function money(value) {
  const n = Number(value ?? 0);
  try {
    return n.toLocaleString(undefined, { style: "currency", currency: CURRENCY });
  } catch {
    return n.toFixed(2) + " " + CURRENCY;
  }
}

/** "in 23 hours" / "in 4 minutes" - how long a block still has to run. */
function until(iso) {
  if (!iso) return null;
  const ms = new Date(iso).getTime() - Date.now();
  if (isNaN(ms) || ms <= 0) return null;
  const hours = Math.floor(ms / 3600000);
  if (hours >= 1) return `${hours} hour${hours === 1 ? "" : "s"}`;
  const mins = Math.max(1, Math.floor(ms / 60000));
  return `${mins} minute${mins === 1 ? "" : "s"}`;
}

/* ------------------------------------------------------------------ auth */

/**
 * The demo has no login, so the page mints a token for the id the game page stored. Identical to what
 * the history page does - and the id is only ever used to obtain a token, never sent to the RG API,
 * which takes no player id at all and acts solely on the authenticated caller.
 */
async function ensureToken() {
  if (token) return token;
  let playerId = localStorage.getItem(PLAYER_KEY);
  if (!playerId) {
    playerId = "demo-" + Math.random().toString(36).slice(2, 10);
    localStorage.setItem(PLAYER_KEY, playerId);
  }
  const res = await fetch("/api/v1/dev/token", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ playerId, sessionId: crypto.randomUUID(), currency: CURRENCY,
                           roles: ["PLAYER"], ttlMinutes: 60 }),
  });
  if (!res.ok) throw new Error("Could not obtain a demo token");
  token = (await res.json()).token;
  return token;
}

async function api(path, method = "GET", body) {
  const t = await ensureToken();
  const res = await fetch(API + path, {
    method,
    headers: { Authorization: "Bearer " + t, "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const payload = await res.json().catch(() => ({}));
  if (!res.ok) {
    const err = new Error(payload.message || `HTTP ${res.status}`);
    err.code = payload.code;
    throw err;
  }
  return payload;
}

/* ------------------------------------------------------------------ render */

function render(s) {
  state = s;
  root.innerHTML = `
    ${renderHeader(s)}
    ${s.selfExcluded ? "" : renderConsumption(s)}
    ${s.selfExcluded ? "" : renderLimits(s)}
    ${renderBreaks(s)}
    ${renderExplainer()}
  `;
  wire();
}

/**
 * The verdict strip. Self-exclusion gets its own treatment rather than a red variant of the limit
 * banner: it is a different thing, with no path back, and dressing it as a temporary block would be a
 * lie told in CSS.
 */
function renderHeader(s) {
  if (s.selfExcluded) {
    return `
      <section class="rg-block is-excluded">
        <div class="rg-block-mark" aria-hidden="true">⛔</div>
        <div>
          <span class="vx-lab">Account closed to play</span>
          <h1>You are self-excluded</h1>
          <p>This account cannot be used to play. Limits can no longer be changed, and there is no
             option here to reverse it - that is what self-exclusion means.</p>
          <p class="rg-demo-note">This is a demo, so the button below restores the account. On a real
             platform there is no such button, and the block is lifted only through a support process
             with its own waiting period.</p>
          <button class="vx-cta rg-danger" id="rgDevReset" type="button">
            Reset demo account <span class="arw">→</span></button>
        </div>
      </section>`;
  }
  if (!s.canPlay) {
    const resumes = until(s.blockedUntil);
    return `
      <section class="rg-block is-blocked">
        <div class="rg-block-mark" aria-hidden="true">⏸</div>
        <div>
          <span class="vx-lab">Play paused</span>
          <h1>${esc(limitTitle(s.blockedBy))}</h1>
          <p>${esc(limitBody(s, resumes))}</p>
        </div>
      </section>`;
  }
  return `
    <section class="rg-block is-ok">
      <div class="rg-block-mark" aria-hidden="true">✓</div>
      <div>
        <span class="vx-lab">Responsible Gaming</span>
        <h1>Your limits are active</h1>
        <p>Every limit below is enforced on the server, inside the same transaction that moves the
           money. Nothing here depends on this page being open, or on the client agreeing.</p>
      </div>
    </section>`;
}

function limitTitle(blockedBy) {
  switch (blockedBy) {
    case "SESSION_DURATION": return "You have reached your session time limit";
    case "LOSS": return "You have reached your loss limit";
    case "WAGER": return "You have reached your wager limit";
    case "COOL_OFF": return "You are taking a break";
    default: return "Play is paused";
  }
}

function limitBody(s, resumes) {
  if (s.blockedBy === "COOL_OFF") {
    return resumes ? `Your break ends in ${resumes}.` : "Your break is ending shortly.";
  }
  if (s.blockedBy === "SESSION_DURATION") {
    return `You have played for ${s.sessionMinutesUsed} minutes. The clock restarts after a break.`;
  }
  return "This limit resets at the start of your next period.";
}

/** Live consumption: the part that lets someone stop before a limit stops them. */
function renderConsumption(s) {
  const bars = [
    bar("Session time", s.sessionMinutesUsed, s.sessionLimitMinutes,
        (v) => `${v} min`, s.sessionLimitMinutes),
    bar("Net loss", s.netLoss, s.lossLimit, money, s.lossLimit),
    bar("Total wagered", s.wagered, s.wagerLimit, money, s.wagerLimit),
  ].filter(Boolean).join("");

  if (!bars) {
    return `<section class="rg-sec"><h2>Where you are</h2>
              <p class="rg-empty">No limits set, so there is nothing to measure against yet.</p>
            </section>`;
  }
  return `
    <section class="rg-sec">
      <h2>Where you are</h2>
      <p class="rg-sec-sub">Measured from the wallet ledger, not a counter kept beside it - the same
         rows an auditor would read.</p>
      <div class="rg-bars">${bars}</div>
    </section>`;
}

function bar(label, used, limit, fmt, rawLimit) {
  if (rawLimit == null) return "";
  const usedN = Number(used ?? 0);
  const limitN = Number(rawLimit);
  const pct = limitN > 0 ? Math.max(0, Math.min(100, (usedN / limitN) * 100)) : 0;
  const tone = pct >= 100 ? "is-full" : pct >= 75 ? "is-high" : "";
  return `
    <div class="rg-bar ${tone}">
      <div class="rg-bar-head">
        <span class="rg-bar-k">${esc(label)}</span>
        <span class="rg-bar-v vx-num">${esc(fmt(used))} <i>/ ${esc(fmt(rawLimit))}</i></span>
      </div>
      <div class="rg-bar-track"><div class="rg-bar-fill" style="width:${pct.toFixed(1)}%"></div></div>
    </div>`;
}

function renderLimits(s) {
  return `
    <section class="rg-sec">
      <h2>Your limits</h2>
      <p class="rg-sec-sub">You can always make a limit stricter. Loosening one is capped by the
         ruleset, so this panel cannot be used to opt out of it.</p>
      <form class="rg-form" id="rgLimits">
        ${field("sessionLimitMinutes", "Session time limit", "minutes", s.sessionLimitMinutes, 1)}
        ${field("lossLimit", "Loss limit", CURRENCY, s.lossLimit, 0.01)}
        ${field("wagerLimit", "Wager limit", CURRENCY, s.wagerLimit, 0.01)}
        ${field("realityCheckMinutes", "Reality check every", "minutes", s.realityCheckMinutes, 1)}
        <button class="vx-cta" type="submit">Save limits <span class="arw">→</span></button>
      </form>
    </section>`;
}

function field(name, label, unit, value, step) {
  return `
    <label class="rg-field">
      <span class="rg-field-k">${esc(label)}</span>
      <span class="rg-field-in">
        <input type="number" name="${name}" step="${step}" min="0"
               value="${value == null ? "" : esc(value)}" inputmode="decimal" />
        <span class="rg-field-u">${esc(unit)}</span>
      </span>
    </label>`;
}

/** The two ways out. Both destructive, both behind a confirmation, and not styled alike. */
function renderBreaks(s) {
  if (s.selfExcluded) return "";
  return `
    <section class="rg-sec rg-breaks">
      <h2>Take a break</h2>
      <div class="rg-break-grid">
        <div class="rg-break">
          <h3>Cool-off</h3>
          <p>Blocks play for a fixed period. It can be extended while it runs, but never shortened.</p>
          <div class="rg-break-row">
            <select id="rgCoolHours" aria-label="Cool-off length">
              <option value="1">1 hour</option>
              <option value="24" selected>24 hours</option>
              <option value="168">7 days</option>
              <option value="720">30 days</option>
            </select>
            <button class="vx-cta rg-warn" id="rgCoolOff" type="button">Start cool-off</button>
          </div>
        </div>
        <div class="rg-break">
          <h3>Self-exclusion</h3>
          <p>Closes the account to play permanently. There is no undo, and no limit can be changed
             afterwards.</p>
          <button class="vx-cta rg-danger" id="rgSelfExclude" type="button">Self-exclude…</button>
        </div>
      </div>
    </section>`;
}

function renderExplainer() {
  return `
    <section class="rg-sec rg-why">
      <h2>How this is enforced</h2>
      <p>Each limit is checked inside the same database transaction that debits the stake, so there is
         no window where a bet slips past a limit that had already been reached. The same check decides
         whether the game even offers you a spin: when a limit is reached the server stops returning
         <code>SPIN</code> in the round's available actions, so the button dies because the server
         withdrew the action - not because this page hid it.</p>
      <p>A limit stops your <em>next</em> stake and never a round already in play. A bought feature you
         have paid for always finishes, because taking the money and withholding what it bought would be
         a worse outcome than the one the limit exists to prevent.</p>
    </section>`;
}

/* ------------------------------------------------------------------ actions */

function wire() {
  const form = document.getElementById("rgLimits");
  if (form) form.addEventListener("submit", saveLimits);

  const cool = document.getElementById("rgCoolOff");
  if (cool) cool.addEventListener("click", startCoolOff);

  const exclude = document.getElementById("rgSelfExclude");
  if (exclude) exclude.addEventListener("click", confirmSelfExclusion);

  const reset = document.getElementById("rgDevReset");
  if (reset) reset.addEventListener("click", devReset);
}

async function saveLimits(e) {
  e.preventDefault();
  const data = new FormData(e.target);
  const body = {};
  for (const [k, v] of data.entries()) {
    if (v !== "") body[k] = Number(v);
  }
  try {
    render(await api("/limits", "PUT", body));
    toast("Limits saved", "ok");
  } catch (err) {
    toast(err.message, "error");
  }
}

async function startCoolOff() {
  const hours = Number(document.getElementById("rgCoolHours").value);
  if (!confirm(`Block play for ${hours} hour${hours === 1 ? "" : "s"}? This cannot be shortened.`)) {
    return;
  }
  try {
    render(await api("/cool-off", "POST", { hours }));
    toast("Cool-off started", "ok");
  } catch (err) {
    toast(err.message, "error");
  }
}

/**
 * Two steps, deliberately. The server also refuses anything but the typed word, so a stray click or a
 * retried request cannot close an account - the confirmation is not merely a client-side courtesy.
 */
async function confirmSelfExclusion() {
  const typed = prompt(
    "Self-exclusion is permanent and cannot be undone.\n\nType SELF-EXCLUDE to confirm.");
  if (typed !== "SELF-EXCLUDE") {
    if (typed !== null) toast("Not confirmed - nothing changed", "");
    return;
  }
  try {
    render(await api("/self-exclude", "POST", { confirm: "SELF-EXCLUDE" }));
    toast("Account closed to play", "");
  } catch (err) {
    toast(err.message, "error");
  }
}

async function devReset() {
  try {
    render(await api("/dev/reset", "POST"));
    toast("Demo account restored", "ok");
  } catch (err) {
    toast(err.message, "error");
  }
}

/* ------------------------------------------------------------------ boot */

async function load() {
  try {
    render(await api("/status"));
  } catch (err) {
    root.innerHTML = `<section class="rg-block is-blocked"><div class="rg-block-mark">⚠</div>
      <div><h1>Couldn’t load your limits</h1><p>${esc(err.message)}</p></div></section>`;
  }
}

document.addEventListener("DOMContentLoaded", load);
