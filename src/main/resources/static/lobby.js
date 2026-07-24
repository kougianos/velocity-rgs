"use strict";

/* =========================================================================
 * Velocity RGS - lobby (reworked "Velocity" identity, vertical slice).
 *
 * Fetches the live game catalog (see games.js) and renders it into the kinetic
 * dark cockpit: a hero featuring the flagship game, then category rails. Every
 * detail - title, tagline, hue, headline math - still comes straight from the
 * server config; nothing about a game is hardcoded here. Each game tints one
 * shared card chassis via a --vx-hue custom property mapped from its theme.
 * ======================================================================= */

const fmtInt = (n) => Number(n ?? 0).toLocaleString();
const titleCase = (s) => (s || "").charAt(0).toUpperCase() + (s || "").slice(1).toLowerCase();

/* esc() and renderFeatureCards() live in games.js - the game page's info modal renders the same
   feature cards, so the renderer is shared rather than duplicated per page. */

/** Map a game's server-defined theme to its accent hue token. */
function hueFor(theme) {
  switch (theme) {
    case "inferno": return "var(--vx-inferno)";
    case "fire": return "var(--vx-aztec)";
    case "frost": return "var(--vx-frost)";
    case "jade": return "var(--vx-jade)";
    case "gilded": return "var(--vx-gold, #f5c518)";
    case "hoard": return "var(--vx-violet)";
    case "roulette": return "var(--vx-emerald)";
    case "blackjack": return "var(--vx-violet)";
    default: return "var(--vx-ignite)";
  }
}

const isSlot = (g) => g.gameType === "SLOT" || !g.gameType;

async function loadGames() {
  const root = document.getElementById("lobbyRoot");
  try {
    const games = await fetchCatalog();
    if (!Array.isArray(games) || games.length === 0) {
      renderError("No games are on the shelf yet", "The server returned an empty catalog. Check the game registry and reload.");
      return;
    }
    render(games);
  } catch (e) {
    renderError("Couldn’t load the games", "The catalog didn’t respond. Check your connection and try again.");
  }
}

function render(games) {
  const root = document.getElementById("lobbyRoot");
  const slots = games.filter(isSlot);
  const roulette = games.filter((g) => g.gameType === "ROULETTE");
  const blackjack = games.filter((g) => g.gameType === "BLACKJACK");
  const tables = [...roulette, ...blackjack];

  // Flagship = the biggest max-win slot (the boldest headline), else the first game.
  const featured = slots.slice().sort((a, b) => (b.maxWinMultiplier || 0) - (a.maxWinMultiplier || 0))[0] || games[0];

  root.innerHTML = "";
  root.appendChild(renderHero(featured));
  root.appendChild(renderProofBand());
  root.appendChild(renderRgBand());
  if (slots.length) root.appendChild(renderRail("Slots", slots, "var(--vx-ignite)"));
  if (tables.length) root.appendChild(renderRail("Table games", tables, "var(--vx-emerald)"));

  startStreaks(document.getElementById("vxStreaks"));
}

/**
 * The platform band: what the server does, stated once, above the shelf.
 *
 * Hardcoded on purpose, unlike the per-game feature chips - those are derived from each game's math
 * config because a game can stop shipping a mechanic, whereas this describes the platform itself. It
 * moves when the platform moves, which is a code change either way.
 */
function renderProofBand() {
  const sec = document.createElement("section");
  sec.className = "vx-proof";
  sec.innerHTML = `
    <div class="vx-proof-copy">
      <div class="vx-proof-eye"><span class="tick"></span><span class="vx-lab">Platform · Provable fairness</span></div>
      <h2>Every round leaves a receipt</h2>
      <p class="vx-proof-lead">
        Outcomes are decided server-side, and the RNG draws behind them are kept. Any round can be re-run
        through the same engine later and has to land on the same board - and now that proof has a URL
        you can hand to someone with no account.
      </p>
      <div class="vx-proof-cta">
        <a class="vx-cta" href="/history.html">See it on a real round <span class="arw">→</span></a>
        <span class="vx-proof-path vx-num">spin → History → Share proof</span>
      </div>
    </div>
    <ul class="vx-proof-grid">
      <li class="vx-proof-card">
        <span class="vx-proof-i" aria-hidden="true">⟲</span>
        <h3>Deterministic replay</h3>
        <p>The recorded draws go back through the engine that produced them. A cascading round replays
           <em>every</em> drop - reproducing only the opening board would prove nothing about the tumble.</p>
      </li>
      <li class="vx-proof-card">
        <span class="vx-proof-i" aria-hidden="true">🔗</span>
        <h3>Signed, expiring links</h3>
        <p>The round id lives inside the signature and the endpoint takes no round parameter, so a link
           cannot be pointed at anything else. Anonymous, and dead after 24 hours.</p>
      </li>
      <li class="vx-proof-card">
        <span class="vx-proof-i" aria-hidden="true">✓</span>
        <h3>Verified before it is shared</h3>
        <p>A link is only signed for a round that reconstructs right now. You cannot send a proof that
           does not hold - and the page re-checks it again when it is opened.</p>
      </li>
      <li class="vx-proof-card">
        <span class="vx-proof-i" aria-hidden="true">⇜</span>
        <h3>The features replay too</h3>
        <p>A <em>walking wild</em> steps one reel left each free spin, so its board depends on the spin
           before it. That carry is recorded on the round, which is what makes the best round in the
           game provable at all.</p>
      </li>
    </ul>`;
  return sec;
}

/**
 * The Responsible Gaming band (§4.2).
 *
 * Its own band rather than a fourth card on the proof one, because it makes a different claim.
 * Provable fairness is about whether an outcome can be checked; this is about whether the platform
 * will stop taking a bet. Merging them would blur both.
 *
 * Hardcoded like the proof band, and for the same reason: it describes the platform, not a game.
 */
function renderRgBand() {
  const sec = document.createElement("section");
  sec.className = "vx-proof vx-rg-band";
  sec.innerHTML = `
    <div class="vx-proof-copy">
      <div class="vx-proof-eye"><span class="tick"></span><span class="vx-lab">Platform · Responsible Gaming</span></div>
      <h2>Limits that actually stop the bet</h2>
      <p class="vx-proof-lead">
        Session time, loss and wager limits, a reality check, a cool-off and self-exclusion. Each one is
        checked inside the same database transaction that debits the stake, so there is no window where
        a bet slips past a limit that had already been reached.
      </p>
      <div class="vx-proof-cta">
        <a class="vx-cta" href="/rg.html">Set your limits <span class="arw">→</span></a>
        <span class="vx-proof-path vx-num">set a limit → keep playing → watch it bite</span>
      </div>
    </div>
    <ul class="vx-proof-grid">
      <li class="vx-proof-card">
        <span class="vx-proof-i" aria-hidden="true">⏸</span>
        <h3>The button dies server-side</h3>
        <p>When a limit is reached the server stops returning <em>SPIN</em> in the round's available
           actions. The button greys out because the action was withdrawn, not because the client
           hid it.</p>
      </li>
      <li class="vx-proof-card">
        <span class="vx-proof-i" aria-hidden="true">📊</span>
        <h3>Measured from the ledger</h3>
        <p>Consumption is derived from the wallet transactions themselves, not a counter kept beside
           them. A limit is enforced against the same rows an auditor would read.</p>
      </li>
      <li class="vx-proof-card">
        <span class="vx-proof-i" aria-hidden="true">🎁</span>
        <h3>It never strands a paid feature</h3>
        <p>A limit stops your <em>next</em> stake, never the round in flight. Free spins you have
           already bought always play out - taking the money and withholding the feature would be the
           worse outcome.</p>
      </li>
    </ul>`;
  return sec;
}

function renderHero(g) {
  const hero = document.createElement("section");
  hero.className = "vx-hero";
  hero.style.setProperty("--vx-hue", hueFor(g.theme));
  const rtp = Number(g.targetRtp).toFixed(isSlot(g) ? 2 : 2);
  const maxWin = isSlot(g)
    ? `${fmtInt(g.maxWinMultiplier)}×`
    : `${fmtInt((g.roulette && g.roulette.maxPayoutMultiplier) || (g.blackjack ? 2.5 : 0))}×`;

  hero.innerHTML = `
    <div class="vx-hero-copy">
      <div class="vx-hero-eye"><span class="tick"></span><span class="vx-lab">Featured · ${g.volatility || "Table"} volatility</span></div>
      <h1>${g.title}</h1>
      <p class="vx-tag">${g.description || g.tagline || ""}</p>
      <div class="vx-telem" role="group" aria-label="Game telemetry">
        <div class="cell"><div class="k">RTP</div><div class="v vx-num">${rtp}%</div></div>
        <div class="cell"><div class="k">Max win</div><div class="v vx-num gold">${maxWin}</div></div>
        <div class="cell"><div class="k">Volatility</div><div class="v vx-num">${(g.volatility || "-").toUpperCase()}</div></div>
      </div>
      <a class="vx-cta" href="game.html?game=${encodeURIComponent(g.gameId)}">Play ${g.title} <span class="arw">→</span></a>
    </div>
    <div class="vx-stage">
      <canvas id="vxStreaks" aria-hidden="true"></canvas>
      <span class="vx-stage-tag">Live · certified RNG</span>
      <span class="vx-stage-glyph">${g.logo}</span>
    </div>`;
  return hero;
}

function renderRail(title, games, hue) {
  const sec = document.createElement("section");
  sec.className = "vx-sec";

  const head = document.createElement("div");
  head.className = "vx-sec-head";
  head.style.setProperty("--vx-sec", hue);
  head.innerHTML = `<h2>${title}</h2><span class="rule"></span><span class="count vx-num">${String(games.length).padStart(2, "0")}</span>`;
  sec.appendChild(head);

  const grid = document.createElement("div");
  grid.className = "vx-grid";
  for (const g of games) grid.appendChild(renderCard(g));
  sec.appendChild(grid);
  return sec;
}

/**
 * One game card. The card is an <article>, not an <a>: it carries a real <button> for the info sheet,
 * and interactive content cannot be nested inside a link. "Click anywhere to play" survives via the
 * stretched-link pattern - .vx-play's ::after covers the whole card - so Play stays a genuine link
 * (keyboard, middle-click, open-in-new-tab all work) while the info button sits above it on z-index.
 */
function renderCard(g) {
  const rtp = Number(g.targetRtp).toFixed(isSlot(g) ? 1 : 2);
  const card = document.createElement("article");
  card.className = "vx-card";
  card.style.setProperty("--vx-hue", hueFor(g.theme));

  const badge = cardBadge(g);
  const stats =
    g.gameType === "ROULETTE" ? rouletteStats(g, rtp)
    : g.gameType === "BLACKJACK" ? blackjackStats(g, rtp)
    : slotStats(g, rtp);

  // Slots also badge their win model ("243 Ways" / "20 Paylines") straight from the catalog.
  const model = isSlot(g) && g.winModelLabel
    ? `<span class="vx-model">${esc(g.winModelLabel)}</span>` : "";

  // The mechanics that *define* this game, flagged as headline by the server. Merchandising the
  // cascades/respins on the card itself, not only behind the info button - a player scanning the rail
  // should be able to see which game tumbles and which one locks coins.
  const chips = headlineFeatures(g)
    .map((f) => `<span class="vx-chip">${esc(f.icon)} ${esc(f.name)}</span>`).join("");

  card.innerHTML = `
    <div class="vx-art">${model}<span class="glyph">${esc(g.logo)}</span><span class="vx-vol">${esc(badge)}</span></div>
    <div class="vx-body">
      <h3>${esc(g.title)}</h3>
      <p class="vx-cardtag">${esc(g.tagline)}</p>
      <div class="vx-chips">${chips}</div>
      <div class="vx-stats">${stats}</div>
      <div class="vx-actions">
        <a class="vx-play" href="game.html?game=${encodeURIComponent(g.gameId)}">Play <span class="arw">→</span></a>
        <button class="vx-info" type="button" aria-haspopup="dialog"
                aria-label="Game info and features for ${esc(g.title)}">
          <span class="vx-info-i" aria-hidden="true">i</span><span class="vx-info-t">Info</span>
        </button>
      </div>
    </div>`;

  card.querySelector(".vx-info").addEventListener("click", (e) => openSheet(g, e.currentTarget));
  return card;
}

/** At most two server-flagged signature mechanics - enough to characterise a game, not enough to crowd. */
function headlineFeatures(g) {
  return (g.features || []).filter((f) => f.headline).slice(0, 2);
}

/** Corner badge: volatility for slots, the defining edge for table games. */
function cardBadge(g) {
  if (g.gameType === "ROULETTE") return "2.70% edge";
  if (g.gameType === "BLACKJACK") {
    const b = g.blackjack || {};
    return `S17 · ${b.blackjackPayoutLabel || "3:2"}`;
  }
  return `${g.volatility || ""} vol`;
}

function slotStats(g, rtp) {
  return (
    stat("RTP", `${rtp}%`) +
    stat("Max win", `${fmtInt(g.maxWinMultiplier)}×`) +
    stat(...winModelStat(g))
  );
}

/**
 * The game's win model as a [label, value] pair - server-named via the catalog's winModel /
 * winModelLabel / waysCount, so the lobby can badge "243 Ways" before a single spin has happened
 * rather than inferring it from a null lineId on a win it has not seen yet.
 */
function winModelStat(g) {
  if (g.winModel === "WAYS") return ["Ways", fmtInt(g.waysCount)];
  return ["Lines", (g.paylines || []).length || "-"];
}
function rouletteStats(g, rtp) {
  const r = g.roulette || {};
  return stat("RTP", `${rtp}%`) + stat("Pockets", r.pocketCount ?? 37) + stat("Top pay", "35:1");
}
function blackjackStats(g, rtp) {
  const b = g.blackjack || {};
  return stat("RTP", `${rtp}%`) + stat("Decks", b.decks ?? "-") + stat("BJ pays", b.blackjackPayoutLabel || "3:2");
}
function stat(label, value) {
  return `<div class="s"><span class="sl">${label}</span><span class="sv">${value}</span></div>`;
}

/* =========================================================================
 * Game info sheet - the "what's actually in this game" surface.
 *
 * One component, two presentations: a centered dialog on desktop, a bottom sheet
 * on phones (drag-to-dismiss, thumb-reachable CTA, safe-area aware). Both render
 * the same server-derived content, so there is no second code path to keep in step.
 *
 * The Features tab is built from catalog `features`, which the server derives from
 * the math blocks that switch each mechanic on (see GameFeatureFactory) - the lobby
 * cannot advertise a mechanic the engine does not run. The Details tab is the
 * hand-authored `info` block (prose + spec sheet) behind it.
 * ======================================================================= */

const MOBILE = "(max-width: 500px)";
const sheet = { root: null, panel: null, scroll: null, invoker: null, game: null, hideTimer: null };

/** Build the sheet DOM once and reuse it - every game renders into the same shell. */
function ensureSheet() {
  if (sheet.root) return sheet.root;

  const root = document.createElement("div");
  root.className = "vx-sheet hidden";
  root.setAttribute("role", "dialog");
  root.setAttribute("aria-modal", "true");
  root.setAttribute("aria-labelledby", "vxSheetTitle");
  root.innerHTML = `
    <div class="vx-sheet-scrim" data-close></div>
    <div class="vx-sheet-panel">
      <div class="vx-sheet-grab" aria-hidden="true"></div>
      <header class="vx-sheet-head">
        <span class="vx-sheet-logo"></span>
        <div class="vx-sheet-ident">
          <h2 id="vxSheetTitle"></h2>
          <p class="vx-sheet-tag"></p>
        </div>
        <button class="vx-sheet-x" type="button" data-close aria-label="Close">×</button>
      </header>
      <div class="vx-sheet-telem"></div>
      <div class="vx-sheet-tabs" role="tablist">
        <button role="tab" type="button" data-tab="features" aria-selected="true">Features</button>
        <button role="tab" type="button" data-tab="details" aria-selected="false">Details</button>
      </div>
      <div class="vx-sheet-scroll">
        <div class="vx-sheet-pane" data-pane="features"></div>
        <div class="vx-sheet-pane hidden" data-pane="details"></div>
      </div>
      <footer class="vx-sheet-foot">
        <p class="vx-sheet-trust">
          <span class="dot"></span>Every round is decided server-side, persisted, and replayable
          bit-exact from its own RNG draws.
        </p>
        <a class="vx-sheet-cta"></a>
      </footer>
    </div>`;
  document.body.appendChild(root);

  sheet.root = root;
  sheet.panel = root.querySelector(".vx-sheet-panel");
  sheet.scroll = root.querySelector(".vx-sheet-scroll");

  root.addEventListener("click", (e) => { if (e.target.closest("[data-close]")) closeSheet(); });
  for (const tab of root.querySelectorAll("[data-tab]")) {
    tab.addEventListener("click", () => selectTab(tab.dataset.tab));
  }
  // Escape lives on the document, not the dialog: clicking a paragraph inside the sheet moves focus to
  // <body>, and a listener bound to the dialog would stop hearing keys at exactly that point.
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && !root.classList.contains("hidden")) closeSheet();
  });
  // Focus stays inside while open: a dialog you can Tab out of is a dialog screen readers wander off.
  root.addEventListener("keydown", (e) => {
    if (e.key !== "Tab") return;
    const focusable = [...root.querySelectorAll("button, a[href], [tabindex]:not([tabindex='-1'])")]
      .filter((el) => el.offsetParent !== null);
    if (!focusable.length) return;
    const first = focusable[0], last = focusable[focusable.length - 1];
    if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
    else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
  });
  initSheetDrag();
  return root;
}

function openSheet(g, invoker) {
  ensureSheet();
  sheet.game = g;
  sheet.invoker = invoker || null;
  sheet.root.style.setProperty("--vx-hue", hueFor(g.theme));

  sheet.root.querySelector(".vx-sheet-logo").textContent = g.logo || "🎰";
  sheet.root.querySelector("#vxSheetTitle").textContent = g.title || "";
  sheet.root.querySelector(".vx-sheet-tag").textContent = g.tagline || "";
  sheet.root.querySelector(".vx-sheet-telem").innerHTML = sheetTelemetry(g);
  sheet.root.querySelector("[data-pane='features']").innerHTML = renderFeatures(g);
  sheet.root.querySelector("[data-pane='details']").innerHTML = renderDetails(g);

  const cta = sheet.root.querySelector(".vx-sheet-cta");
  cta.href = `game.html?game=${encodeURIComponent(g.gameId)}`;
  cta.innerHTML = `Play ${esc(g.title)} <span class="arw">→</span>`;

  selectTab("features");
  sheet.scroll.scrollTop = 0;
  // Reopening during the closing animation would otherwise let that pending timer hide the sheet we
  // are in the middle of showing.
  clearTimeout(sheet.hideTimer);
  // Lock the page behind the sheet so a scroll gesture that runs past the end of the sheet does not
  // start scrolling the lobby underneath it.
  document.body.classList.add("vx-locked");
  sheet.root.classList.remove("hidden");
  // Force a reflow so the transition has a start value to animate from, rather than deferring the
  // class to rAF - a backgrounded or non-painting tab never runs that callback, and the sheet would
  // then sit there at opacity 0 having been "opened".
  void sheet.root.offsetHeight;
  sheet.root.classList.add("open");
  sheet.root.querySelector(".vx-sheet-x").focus();
}

function closeSheet() {
  if (!sheet.root || sheet.root.classList.contains("hidden")) return;
  sheet.root.classList.remove("open");
  document.body.classList.remove("vx-locked");
  sheet.panel.style.transform = "";
  const done = () => sheet.root.classList.add("hidden");
  clearTimeout(sheet.hideTimer);
  if (matchMedia("(prefers-reduced-motion: reduce)").matches) done();
  else sheet.hideTimer = setTimeout(done, 300);   // outlasts the slower (mobile) slide-out
  // Send focus back where it came from, or the keyboard user is dumped at the top of the document.
  if (sheet.invoker) sheet.invoker.focus();
  sheet.invoker = null;
}

function selectTab(name) {
  for (const tab of sheet.root.querySelectorAll("[data-tab]")) {
    tab.setAttribute("aria-selected", String(tab.dataset.tab === name));
  }
  for (const pane of sheet.root.querySelectorAll("[data-pane]")) {
    pane.classList.toggle("hidden", pane.dataset.pane !== name);
  }
  sheet.scroll.scrollTop = 0;
}

/** The headline numbers, shown above the tabs so they survive whichever tab is open. */
function sheetTelemetry(g) {
  const cells = [["RTP", `${Number(g.targetRtp).toFixed(2)}%`]];
  if (isSlot(g)) {
    cells.push(["Max win", `${fmtInt(g.maxWinMultiplier)}×`]);
    cells.push(winModelStat(g));
  } else if (g.gameType === "ROULETTE") {
    cells.push(["Pockets", (g.roulette || {}).pocketCount ?? 37]);
    cells.push(["Top pay", "35:1"]);
  } else {
    cells.push(["Decks", (g.blackjack || {}).decks ?? "-"]);
    cells.push(["BJ pays", (g.blackjack || {}).blackjackPayoutLabel || "3:2"]);
  }
  cells.push(["Volatility", (g.volatility || "-").toUpperCase()]);
  // Namespaced classes, not the `cell`/`k`/`v` the hero uses: `.cell` is the slot reel's global class
  // and carries `aspect-ratio: 1/1`, which turns anything reusing the name into a square.
  return cells.map(([k, v]) =>
    `<div class="vx-tcell"><div class="vx-tk">${esc(k)}</div>` +
    `<div class="vx-tv vx-num">${esc(v)}</div></div>`).join("");
}

/** The mechanics themselves - one card per feature, signature ones flagged and accented. */
function renderFeatures(g) {
  if (!(g.features || []).length) {
    return `<p class="vx-sheet-empty">This game ships no configurable mechanics.</p>`;
  }
  return renderFeatureCards(g.features);
}

/** The hand-authored spec sheet: marketing prose, stat cards, and the labelled spec rows. */
function renderDetails(g) {
  const info = g.info || {};
  const parts = [];
  if (g.description) parts.push(`<p class="vx-sheet-lede">${esc(g.description)}</p>`);
  for (const p of info.paragraphs || []) parts.push(`<p>${esc(p)}</p>`);

  if ((info.stats || []).length) {
    parts.push(`<div class="vx-sheet-stats">${(info.stats).map((s) =>
      `<div><span>${esc(s.label)}</span><strong>${esc(s.value)}</strong></div>`).join("")}</div>`);
  }
  if ((info.specs || []).length) {
    parts.push(`<dl class="vx-sheet-specs">${(info.specs).map((s) =>
      `<dt>${esc(s.label)}</dt><dd>${(s.values || []).map((v) =>
        `<span>${esc(v)}</span>`).join("")}</dd>`).join("")}</dl>`);
  }
  return parts.join("") || `<p class="vx-sheet-empty">No further detail published for this game.</p>`;
}

/**
 * Mobile drag-to-dismiss. A bottom sheet you can only close by aiming at a small × in the far corner
 * is a bottom sheet fighting the platform: the whole point of the shape is that it is dismissed with
 * the thumb that opened it. Drags on the grab handle and header (never the scrolling body, which owns
 * its own gesture) track the finger, then either spring back or fall away past the threshold.
 */
function initSheetDrag() {
  let startY = 0, startT = 0, dy = 0, dragging = false;

  const onDown = (e) => {
    if (!matchMedia(MOBILE).matches || e.target.closest("[data-close], [data-tab]")) return;
    dragging = true; startY = e.clientY; startT = performance.now(); dy = 0;
    sheet.panel.style.transition = "none";
    sheet.panel.setPointerCapture(e.pointerId);
  };
  const onMove = (e) => {
    if (!dragging) return;
    dy = Math.max(0, e.clientY - startY);
    sheet.panel.style.transform = `translateY(${dy}px)`;
    sheet.root.style.setProperty("--vx-drag", String(1 - Math.min(dy / 320, 0.6)));
  };
  const onUp = () => {
    if (!dragging) return;
    dragging = false;
    sheet.panel.style.transition = "";
    sheet.root.style.removeProperty("--vx-drag");
    // A short flick should dismiss as readily as a long drag, so velocity counts as well as distance.
    // The flick path still needs real distance behind it, or a fast 20px twitch on the header - which
    // is what a tap registers as if the thumb slips - throws the sheet away.
    const velocity = dy / Math.max(performance.now() - startT, 1);
    if (dy > 110 || (velocity > 0.55 && dy > 45)) closeSheet();
    else sheet.panel.style.transform = "";
  };

  const head = sheet.panel.querySelector(".vx-sheet-head");
  for (const el of [sheet.panel.querySelector(".vx-sheet-grab"), head]) {
    el.addEventListener("pointerdown", onDown);
  }
  sheet.panel.addEventListener("pointermove", onMove);
  sheet.panel.addEventListener("pointerup", onUp);
  sheet.panel.addEventListener("pointercancel", onUp);
}

function renderError(title, message) {
  const root = document.getElementById("lobbyRoot");
  root.innerHTML = `
    <div class="vx-error" role="alert">
      <h2>${title}</h2>
      <p>${message}</p>
      <button class="vx-retry" id="retry">Try again</button>
    </div>`;
  document.getElementById("retry").addEventListener("click", () => {
    root.innerHTML = "";
    loadGames();
  });
}

/* Hero velocity streaks - plasma light lines racing across the flagship stage.
   Canvas (not hand-authored SVG) for the generative motion; static if reduced-motion. */
function startStreaks(c) {
  if (!c) return;
  const ctx = c.getContext("2d");
  const reduce = matchMedia("(prefers-reduced-motion: reduce)").matches;
  let W, H, DPR, lines = [];
  function size() {
    DPR = Math.min(devicePixelRatio || 1, 2);
    W = c.clientWidth; H = c.clientHeight;
    c.width = W * DPR; c.height = H * DPR; ctx.setTransform(DPR, 0, 0, DPR, 0, 0);
  }
  const mk = () => ({ x: Math.random() * W, y: Math.random() * H, len: 40 + Math.random() * 160, sp: 2 + Math.random() * 6, w: 0.6 + Math.random() * 1.8, a: 0.05 + Math.random() * 0.5 });
  function init() { lines = []; for (let i = 0; i < 46; i++) lines.push(mk()); }
  function draw() {
    ctx.clearRect(0, 0, W, H);
    const g = ctx.createRadialGradient(W * 0.72, H * 0.28, 10, W * 0.72, H * 0.28, H * 0.9);
    g.addColorStop(0, "rgba(255,90,44,0.26)"); g.addColorStop(0.5, "rgba(255,46,126,0.10)"); g.addColorStop(1, "rgba(255,46,126,0)");
    ctx.fillStyle = g; ctx.fillRect(0, 0, W, H);
    for (const l of lines) {
      const grad = ctx.createLinearGradient(l.x, l.y, l.x + l.len, l.y - l.len * 0.34);
      grad.addColorStop(0, "rgba(255,107,44,0)");
      grad.addColorStop(0.5, `rgba(255,120,90,${l.a})`);
      grad.addColorStop(1, "rgba(255,46,126,0)");
      ctx.strokeStyle = grad; ctx.lineWidth = l.w;
      ctx.beginPath(); ctx.moveTo(l.x, l.y); ctx.lineTo(l.x + l.len, l.y - l.len * 0.34); ctx.stroke();
      l.x += l.sp; l.y -= l.sp * 0.34;
      if (l.x - l.len > W || l.y + l.len < 0) { Object.assign(l, mk()); l.x = -l.len; l.y = H + Math.random() * 40; }
    }
    if (!reduce) requestAnimationFrame(draw);
  }
  size(); init(); draw();
  addEventListener("resize", () => { size(); init(); if (reduce) draw(); });
}

document.addEventListener("DOMContentLoaded", loadGames);
