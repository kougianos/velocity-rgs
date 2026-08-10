"use strict";

/* =========================================================================
 * Velocity RGS - progressive jackpots on the game page (§2).
 *
 * The lobby strip says the pools exist. This says something the lobby cannot:
 * that *your spin* just fed them. The bar is seeded once from
 * /api/v1/jackpots and thereafter advanced from each spin's own response,
 * which carries both what it contributed and where the pools stand after it.
 *
 * Driven by the response rather than by polling, deliberately. A poll could
 * only show that the numbers were different a moment later - true, and a much
 * weaker thing to have watched than a figure that moves on the click.
 *
 * Mounted only for a game that actually contributes, which the catalog states
 * by carrying a PROGRESSIVE_JACKPOTS feature card. A game that does not feed
 * the pools shows no bar, for the same reason it shows no card.
 * ======================================================================= */

const JP_TIER_ORDER = ["MINI", "MINOR", "MAJOR", "MEGA"];

let jpMounted = false;

function jpBarEl() {
  return document.getElementById("jackpotBar");
}

function jpMoney(value, currency) {
  const symbol = { EUR: "€", USD: "$", GBP: "£" }[currency] || "";
  return symbol + Number(value ?? 0).toLocaleString(undefined,
    { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

/** Whether the catalog says this game feeds the pools. */
function jpGameContributes(game) {
  return Array.isArray(game && game.features)
    && game.features.some((f) => f.key === "PROGRESSIVE_JACKPOTS");
}

/**
 * Mounts the bar for a contributing game and seeds it with the live pools.
 *
 * Failure is silent and simply leaves the bar hidden: the jackpot readout is an ornament on a game
 * that plays perfectly well without it, and a spin page that refused to boot because a decorative
 * fetch failed would be a worse trade than no bar.
 */
async function mountJackpotBar(game) {
  const el = jpBarEl();
  if (!el || jpMounted || !jpGameContributes(game)) return;

  try {
    const res = await fetch(`/api/v1/jackpots?currency=${encodeURIComponent(CURRENCY)}`);
    if (!res.ok) return;
    const pools = await res.json();
    if (!Array.isArray(pools) || pools.length === 0) return;
    renderJackpotBar(pools, null);
    el.classList.remove("hidden");
    jpMounted = true;
    mountJackpotDemo();
  } catch { /* no bar, no noise */ }
}

/**
 * Advances the bar from a spin response.
 *
 * A spin that contributed nothing - a free spin, or jackpots switched off - carries no jackpot object
 * at all, and the bar simply holds its last figures rather than flashing a zero.
 */
function updateJackpotBar(jackpot) {
  if (!jpMounted || !jackpot || !Array.isArray(jackpot.pools) || jackpot.pools.length === 0) return;
  renderJackpotBar(jackpot.pools, jackpot);
}

function renderJackpotBar(pools, contribution) {
  const el = jpBarEl();
  if (!el) return;

  const ordered = pools.slice().sort(
    (a, b) => JP_TIER_ORDER.indexOf(a.tier) - JP_TIER_ORDER.indexOf(b.tier));

  const cells = ordered.map((p) => `
    <div class="jp-cell" data-tier="${rgEsc(p.tier)}">
      <span class="jp-tier">${rgEsc(p.label)}</span>
      <span class="jp-amt">${jpMoney(p.amount, p.currency)}</span>
    </div>`).join("");

  // The contribution is stated at the pool column's precision, not the currency's. 1% of a 1.00 stake
  // is 0.01, but 1% of the minimum 0.20 stake is 0.002 - rounding that to a cent would show a player
  // 0.00 for a contribution that genuinely happened.
  const fed = contribution && Number(contribution.amount) > 0
    ? `<span class="jp-fed">+${Number(contribution.amount).toFixed(4)} from your spin</span>`
    : "";

  el.innerHTML = `
    <div class="jp-head">
      <span class="jp-lab">Progressive jackpots</span>
      ${fed}
    </div>
    <div class="jp-cells">${cells}</div>`;

  if (fed) {
    // Restart the flash on every spin: re-adding a class the element already has does not replay the
    // animation, so it has to be removed and forced to reflow first.
    el.classList.remove("is-fed");
    void el.offsetWidth;
    el.classList.add("is-fed");
  }
}

/* ------------------------------------------------------------------ winning one */

/**
 * Announces a jackpot and explains the drop the bar is about to show.
 *
 * A won pool falls back to its seed, so without this the strip would appear to lose money at the exact
 * moment somebody won it - the one reading a player must not be left to make on their own.
 */
function announceJackpotWin(win) {
  if (!win) return;

  const wrap = document.createElement("div");
  wrap.className = "rg-modal jp-win-modal";
  wrap.innerHTML = `
    <div class="rg-modal-card" role="alertdialog" aria-modal="true" aria-labelledby="jpWinTitle">
      <div style="font-size:34px" aria-hidden="true">🏆</div>
      <h2 id="jpWinTitle">${rgEsc(win.label)} jackpot</h2>
      <p class="jp-win-amt">${jpMoney(win.amount, win.currency)}</p>
      <p>Paid straight to your balance. The pool now resets to
         <strong>${jpMoney(win.resetTo, win.currency)}</strong> and starts climbing again.</p>
      <div class="jp-win-ids">
        <div><span>Win</span><code>${rgEsc(win.winId)}</code></div>
        <div><span>Transaction</span><code>${rgEsc(win.txId)}</code></div>
      </div>
      <div class="rg-modal-actions">
        <button class="vx-cta" id="jpWinClose" type="button">Nice</button>
      </div>
    </div>`;
  document.body.appendChild(wrap);
  wrap.querySelector("#jpWinClose").addEventListener("click", () => wrap.remove());
}

/* ------------------------------------------------------------------ demo panel */

/**
 * The exactly-once demonstration (§2).
 *
 * Idempotency is the least visible and most consequential property in this codebase, and until now it
 * was provable only by reading tests. This turns it into two buttons: force a win, then re-send that
 * same winning spin under its own Idempotency-Key and read the two responses side by side. Identical
 * payload, balance unchanged, one jackpot_win row.
 *
 * The dice are the only thing rigged. The award path a viewer watches - the compare-and-swap, the audit
 * row, the credit, the pool reset - is the production one.
 */
function mountJackpotDemo() {
  const panel = document.getElementById("jackpotDemo");
  if (!panel) return;
  panel.classList.remove("hidden");

  panel.querySelector("#jpForceWin").addEventListener("click", async () => {
    const tier = panel.querySelector("#jpTier").value;
    try {
      const res = await api("/api/v1/jackpot/dev/force-win", { method: "POST", body: { tier }, track: false });
      toast(res.message, "success");
    } catch (e) {
      handleError("Could not arm the jackpot", e);
    }
  });

  panel.querySelector("#jpReplay").addEventListener("click", async () => {
    const last = window.__lastSpin;
    const out = panel.querySelector("#jpReplayOut");
    if (!last) {
      out.textContent = "Spin once first - there is no request to replay yet.";
      return;
    }
    out.textContent = "Re-sending the last spin with the same Idempotency-Key…";
    try {
      const balanceBefore = (await api("/api/v1/wallet/balance", { track: false })).balance;
      const replay = await api("/api/v1/slot/spin", {
        method: "POST", idempotency: true, idempotencyKey: last.key, body: last.body, track: false,
      });
      const balanceAfter = (await api("/api/v1/wallet/balance", { track: false })).balance;

      out.textContent =
        `// Idempotency-Key (unchanged)\n${last.key}\n\n` +
        `// balance before replay : ${fmt(balanceBefore)}\n` +
        `// balance after  replay : ${fmt(balanceAfter)}\n` +
        `// moved                 : ${fmt(Number(balanceAfter) - Number(balanceBefore))}\n\n` +
        `// replayed response\n${JSON.stringify(replay, null, 2)}`;
    } catch (e) {
      handleError("Replay failed", e);
    }
  });
}
