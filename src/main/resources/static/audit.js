"use strict";

/* =========================================================================
 * Velocity RGS - reconciliation findings (§2).
 *
 * The job compares what the game engine says happened against what the wallet
 * ledger says happened, hourly, and writes a row per player whose totals do
 * not agree. Until now those rows existed and nobody could see them.
 *
 * The page has a second job, and it is the more important one: an empty
 * findings report and a broken findings report look exactly the same. So it
 * also carries a control that plants a credit with no round behind it, and the
 * very next run has to catch it. Proving the report can find something is what
 * makes "no findings" mean anything.
 *
 * The jackpot case is the reason this exists now: a progressive is credited on
 * its own transaction and is not part of the round's total win, so without the
 * audit row every win would look like money appearing from nowhere.
 * ======================================================================= */

const CURRENCY = "EUR";
const PLAYER_KEY = "velocity.playerId";

let token = null;
const root = document.getElementById("auditRoot");

/* ------------------------------------------------------------------ util */

function esc(s) {
  return String(s ?? "").replace(/[&<>"']/g,
    (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

function money(v) {
  return Number(v ?? 0).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function when(iso) {
  return iso ? new Date(iso).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }) : "-";
}

let toastTimer = null;
function toast(message, kind = "") {
  const el = document.getElementById("toast");
  el.textContent = message;
  el.className = "toast " + kind;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.add("hidden"), 3600);
}

/* ------------------------------------------------------------------ auth */

/**
 * The findings endpoints need the ADMIN role, so this mints a token carrying it - the same demo
 * shortcut the game page uses. The player id is the one the rest of the demo is already using, so
 * "plant a credit" lands on an account whose rounds are actually in the window.
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
                           roles: ["PLAYER", "ADMIN"], ttlMinutes: 60 }),
  });
  if (!res.ok) throw new Error("Could not obtain an admin token");
  token = (await res.json()).token;
  return token;
}

async function api(path, method = "GET", body) {
  const t = await ensureToken();
  const res = await fetch(path, {
    method,
    headers: { Authorization: "Bearer " + t, "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const payload = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(payload.message || `HTTP ${res.status}`);
  return payload;
}

/* ------------------------------------------------------------------ render */

function render(findings) {
  const player = localStorage.getItem(PLAYER_KEY) || "-";
  root.innerHTML = `
    ${renderExplainer()}
    ${renderControls(player)}
    ${renderFindings(findings)}`;
  bind();
}

function renderExplainer() {
  return `
    <section class="rg-block">
      <div class="rg-block-mark">⚖</div>
      <div>
        <h1>Reconciliation</h1>
        <p>Every hour the server totals what the game engine says it took and paid, and compares it
           against the wallet ledger. A player whose two sides disagree gets a row here.</p>
        <p class="au-note">A jackpot is credited on its own transaction and is <em>not</em> part of the
           round's win, so without its audit row every progressive payout would show up here as money
           appearing from nowhere. It reconciles because <code>jackpot_win</code> explains it.</p>
      </div>
    </section>`;
}

function renderControls(player) {
  return `
    <section class="au-controls">
      <div class="au-ctl">
        <h2>Run a window now</h2>
        <p>The scheduler covers the last hour every hour. This runs the same comparison on demand.</p>
        <button class="vx-cta" id="auRun" type="button">Run reconciliation <span class="arw">→</span></button>
      </div>
      <div class="au-ctl au-ctl-warn">
        <h2>Give it something to catch</h2>
        <p>An empty report and a broken report look identical. This credits
           <strong>250.00</strong> to <code>${esc(player)}</code> with no round behind it, so the next
           run has to flag it.</p>
        <button class="vx-cta rg-warn" id="auSeed" type="button">Plant an unexplained credit</button>
      </div>
    </section>`;
}

function renderFindings(findings) {
  if (!findings.length) {
    return `
      <section class="au-empty">
        <h2>No findings in the last 24 hours</h2>
        <p>The engine and the ledger agree for every player in the window - jackpot winners included.
           Plant a credit above and run again to confirm this report is actually looking.</p>
      </section>`;
  }

  const rows = findings.map((f) => `
    <tr>
      <td class="au-kind"><span class="au-badge ${f.kind === "CREDIT_MISMATCH" ? "is-credit" : "is-debit"}">
        ${esc(f.kind.replace("_", " "))}</span></td>
      <td><code>${esc(f.playerId)}</code></td>
      <td class="vx-num">${money(f.expectedDebit)} / ${money(f.actualDebit)}</td>
      <td class="vx-num">${money(f.expectedCredit)} / ${money(f.actualCredit)}</td>
      <td class="vx-num au-delta">${money(f.discrepancy)}</td>
      <td>${when(f.bucketStart)}</td>
    </tr>`).join("");

  return `
    <section class="au-results">
      <h2>${findings.length} finding${findings.length === 1 ? "" : "s"}</h2>
      <div class="au-table-wrap">
        <table class="au-table">
          <thead>
            <tr>
              <th>Kind</th><th>Player</th>
              <th>Debit exp / act</th><th>Credit exp / act</th>
              <th>Delta</th><th>Bucket</th>
            </tr>
          </thead>
          <tbody>${rows}</tbody>
        </table>
      </div>
    </section>`;
}

/* ------------------------------------------------------------------ actions */

function bind() {
  document.getElementById("auRun").addEventListener("click", async (e) => {
    e.target.disabled = true;
    try {
      const res = await api("/api/v1/admin/reconciliation/run?hours=24", "POST");
      toast(res.findings === 0
        ? "Ran clean - engine and ledger agree"
        : `${res.findings} discrepanc${res.findings === 1 ? "y" : "ies"} found`,
        res.findings === 0 ? "ok" : "error");
      // Rendered from the run's own result rather than re-fetching the list. The operator asked what
      // THIS run found, and a re-query answers a subtly different question - one whose window is
      // computed a moment later and can miss the rows just written.
      render(res.detail);
    } catch (err) {
      toast(err.message, "error");
      e.target.disabled = false;
    }
  });

  document.getElementById("auSeed").addEventListener("click", async (e) => {
    e.target.disabled = true;
    try {
      const player = localStorage.getItem(PLAYER_KEY);
      await api("/api/v1/admin/reconciliation/seed-unexplained-credit", "POST",
        { playerId: player, currency: CURRENCY, amount: 250.00 });
      toast("Planted. Run reconciliation to see it caught.", "");
    } catch (err) {
      toast(err.message, "error");
    } finally {
      e.target.disabled = false;
    }
  });
}

/* ------------------------------------------------------------------ boot */

async function load() {
  try {
    render(await api("/api/v1/admin/reconciliation/findings?hours=24"));
  } catch (err) {
    root.innerHTML = `<section class="rg-block is-blocked"><div class="rg-block-mark">⚠</div>
      <div><h1>Couldn't load findings</h1><p>${esc(err.message)}</p></div></section>`;
  }
}

document.addEventListener("DOMContentLoaded", load);
