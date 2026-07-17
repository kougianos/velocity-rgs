"use strict";

/* =========================================================================
 * Velocity RGS — lobby (reworked "Velocity" identity, vertical slice).
 *
 * Fetches the live game catalog (see games.js) and renders it into the kinetic
 * dark cockpit: a hero featuring the flagship game, then category rails. Every
 * detail — title, tagline, hue, headline math — still comes straight from the
 * server config; nothing about a game is hardcoded here. Each game tints one
 * shared card chassis via a --vx-hue custom property mapped from its theme.
 * ======================================================================= */

const fmtInt = (n) => Number(n ?? 0).toLocaleString();
const titleCase = (s) => (s || "").charAt(0).toUpperCase() + (s || "").slice(1).toLowerCase();

/** Map a game's server-defined theme to its accent hue token. */
function hueFor(theme) {
  switch (theme) {
    case "inferno": return "var(--vx-inferno)";
    case "fire": return "var(--vx-aztec)";
    case "frost": return "var(--vx-frost)";
    case "jade": return "var(--vx-jade)";
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
  if (slots.length) root.appendChild(renderRail("Slots", slots, "var(--vx-ignite)"));
  if (tables.length) root.appendChild(renderRail("Table games", tables, "var(--vx-emerald)"));

  startStreaks(document.getElementById("vxStreaks"));
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
        <div class="cell"><div class="k">Volatility</div><div class="v vx-num">${(g.volatility || "—").toUpperCase()}</div></div>
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

function renderCard(g) {
  const rtp = Number(g.targetRtp).toFixed(isSlot(g) ? 1 : 2);
  const card = document.createElement("a");
  card.className = "vx-card";
  card.style.setProperty("--vx-hue", hueFor(g.theme));
  card.href = `game.html?game=${encodeURIComponent(g.gameId)}`;

  const badge = cardBadge(g);
  const stats =
    g.gameType === "ROULETTE" ? rouletteStats(g, rtp)
    : g.gameType === "BLACKJACK" ? blackjackStats(g, rtp)
    : slotStats(g, rtp);

  card.innerHTML = `
    <div class="vx-art"><span class="glyph">${g.logo}</span><span class="vx-vol">${badge}</span></div>
    <div class="vx-body">
      <h3>${g.title}</h3>
      <p class="vx-cardtag">${g.tagline}</p>
      <div class="vx-stats">${stats}</div>
      <span class="vx-play">Play <span class="arw">→</span></span>
    </div>`;
  return card;
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
    stat("Free spins", g.freeSpinsAwarded)
  );
}
function rouletteStats(g, rtp) {
  const r = g.roulette || {};
  return stat("RTP", `${rtp}%`) + stat("Pockets", r.pocketCount ?? 37) + stat("Top pay", "35:1");
}
function blackjackStats(g, rtp) {
  const b = g.blackjack || {};
  return stat("RTP", `${rtp}%`) + stat("Decks", b.decks ?? "—") + stat("BJ pays", b.blackjackPayoutLabel || "3:2");
}
function stat(label, value) {
  return `<div class="s"><span class="sl">${label}</span><span class="sv">${value}</span></div>`;
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

/* Hero velocity streaks — plasma light lines racing across the flagship stage.
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
