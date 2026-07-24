"use strict";

/* =========================================================================
 * Velocity RGS - Responsible Gaming on the game page (§4.2).
 *
 * Two jobs, both about making a server-side rule visible where the player is:
 *
 *   1. Render a refused stake. RG_LIMIT_EXCEEDED and RG_SELF_EXCLUDED are two
 *      different things and get two different surfaces - a limit names itself
 *      and says when it resets, self-exclusion is final and offers no way back.
 *      Collapsing them into one error toast would make the taxonomy invisible,
 *      and a taxonomy nobody can tell apart is worth no more than not having
 *      one.
 *
 *   2. Raise the reality check on the configured interval, stating time played
 *      and net position.
 *
 * Nothing here decides anything. The server has already refused the stake, and
 * has already withdrawn SPIN from availableActions - this file only explains
 * what happened.
 * ======================================================================= */

const RG_POLL_MS = 20000;

let rgPollTimer = null;
let rgModalOpen = false;

/* ------------------------------------------------------------------ banner */

function rgBannerEl() {
  return document.getElementById("rgBanner");
}

function rgEsc(s) {
  return String(s ?? "").replace(/[&<>"']/g,
    (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

function rgWhen(iso) {
  if (!iso) return null;
  const ms = new Date(iso).getTime() - Date.now();
  if (isNaN(ms) || ms <= 0) return null;
  const hours = Math.floor(ms / 3600000);
  if (hours >= 1) return `${hours} hour${hours === 1 ? "" : "s"}`;
  const mins = Math.max(1, Math.floor(ms / 60000));
  return `${mins} minute${mins === 1 ? "" : "s"}`;
}

const RG_LIMIT_NAMES = {
  SESSION_DURATION: "session time limit",
  LOSS: "loss limit",
  WAGER: "wager limit",
  COOL_OFF: "cool-off",
};

/**
 * Renders a Responsible Gaming refusal, or returns false if the error was something else.
 *
 * Called from handleError, which every game funnels its failures through, so slots, roulette and
 * blackjack all get this without three copies of it.
 */
function renderRgRefusal(payload) {
  const el = rgBannerEl();
  if (!el || !payload || !payload.code) return false;

  if (payload.code === "RG_SELF_EXCLUDED") {
    el.innerHTML = `
      <div class="rg-banner is-final" role="alert">
        <span class="rg-banner-i" aria-hidden="true">⛔</span>
        <div>
          <span class="rg-banner-t">This account is self-excluded</span>
          Play is closed on this account and there is no way back to it from here.
          <a href="/rg.html">Open Responsible Gaming</a>.
        </div>
      </div>`;
    return true;
  }

  if (payload.code === "RG_LIMIT_EXCEEDED") {
    const ctx = payload.context || {};
    const name = RG_LIMIT_NAMES[ctx.limit] || "limit";
    const resumes = rgWhen(ctx.resetsAt);
    // Usage is shown only when the server sent both halves, and states consumption rather than
    // restating the limit the message has already named. A cool-off has no "limit value" at all, and
    // inventing a figure to fill the slot would be worse than leaving it out.
    const figure = ctx.used && ctx.limitValue
      ? ` You have used <strong>${rgEsc(ctx.used)} of ${rgEsc(ctx.limitValue)}${
          ctx.currency ? " " + rgEsc(ctx.currency) : ""}</strong>.`
      : "";

    el.innerHTML = `
      <div class="rg-banner" role="alert">
        <span class="rg-banner-i" aria-hidden="true">⏸</span>
        <div>
          <span class="rg-banner-t">Play paused by your ${rgEsc(name)}</span>
          ${rgEsc(payload.message || "")}.${figure}
          ${resumes ? ` You can play again in <strong>${rgEsc(resumes)}</strong>.` : ""}
          <a href="/rg.html">Open Responsible Gaming</a>.
        </div>
      </div>`;
    return true;
  }
  return false;
}

function clearRgBanner() {
  const el = rgBannerEl();
  if (el) el.innerHTML = "";
}

/* ------------------------------------------------------------------ reality check */

async function rgStatus() {
  try {
    return await api("/api/v1/rg/status", { track: false });
  } catch {
    return null;
  }
}

/**
 * The reality check: an interruption, not a notification. It states time played and net position and
 * makes the player click through it, which is the entire mechanic - a banner they can ignore is not a
 * reality check.
 */
function showRealityCheck(status) {
  if (rgModalOpen) return;
  rgModalOpen = true;

  const net = Number(status.netLoss ?? 0);
  const wrap = document.createElement("div");
  wrap.className = "rg-modal";
  wrap.innerHTML = `
    <div class="rg-modal-card" role="dialog" aria-modal="true" aria-labelledby="rgRcTitle">
      <div style="font-size:30px" aria-hidden="true">⏱</div>
      <h2 id="rgRcTitle">Reality check</h2>
      <p>A quick pause, as you asked. Here is where you are.</p>
      <div class="rg-modal-stats">
        <div class="rg-modal-stat"><span>Time played</span><strong>${status.sessionMinutesUsed} min</strong></div>
        <div class="rg-modal-stat"><span>Net position</span><strong>${
          net > 0 ? "-" : ""}${fmt(Math.abs(net))}</strong></div>
      </div>
      <div class="rg-modal-actions">
        <button class="vx-cta" id="rgRcContinue" type="button">Keep playing</button>
        <a class="vx-cta rg-warn" href="/rg.html">Review my limits</a>
      </div>
    </div>`;
  document.body.appendChild(wrap);

  wrap.querySelector("#rgRcContinue").addEventListener("click", async () => {
    try {
      await api("/api/v1/rg/reality-check/ack", { method: "POST", track: false });
    } catch { /* the interval simply stays due and the check reappears - never a blocked page */ }
    wrap.remove();
    rgModalOpen = false;
  });
}

async function rgPoll() {
  const status = await rgStatus();
  if (status && status.enabled && status.realityCheckDue) {
    showRealityCheck(status);
  }
}

/**
 * Starts polling once a player is authenticated. Polling rather than pushing on the spin response:
 * the reality check is about elapsed time, so it has to fire on a player who has stopped clicking,
 * which is exactly the player a response-carried flag would never reach.
 */
function startRgWatch() {
  if (rgPollTimer) return;
  rgPoll();
  rgPollTimer = setInterval(rgPoll, RG_POLL_MS);
}
