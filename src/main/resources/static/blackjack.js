"use strict";

/* =========================================================================
 * Velocity RGS - Blackjack game client (vanilla JS).
 * Builds on game-core.js (API, token, toast, info modal). Renders a felt
 * table - dealer row + player hand(s) of .card elements - a chip-based bet
 * selector, and Deal / Hit / Stand / Double / Split / Insurance buttons that
 * are enabled strictly from the server's availableActions. The server is the
 * sole authority on every card, the dealer's hidden hole card, and the result;
 * NO game logic lives here. Entry point: initBlackjackGame(game).
 *
 * Blackjack is multi-step: init -> deal -> action(s) -> settle. The client
 * only sends the chosen action and re-renders the round snapshot it gets back.
 *
 * Wrapped in an IIFE so it can co-exist with slot.js / roulette.js on the game
 * page without global name collisions. Only window.initBlackjackGame is exposed.
 * ======================================================================= */

(() => {

let META = null;
let BET_VALUES = [];     // selectable base stakes
let MAX_BET = Infinity;

const ACTION_BUTTONS = ["DEAL", "HIT", "STAND", "DOUBLE", "SPLIT", "INSURANCE"];

const state = {
  token: null,
  playerId: null,
  sessionId: null,
  sessionVersion: 0,
  balance: 0,
  selectedChip: 0,
  busy: false,
  inRound: false,
};

const $ = (id) => document.getElementById(id);
const els = {};

/* ----------------------------------------------------------------- config */

function applyConfig(game) {
  META = game;
  BET_VALUES = (game.betValues || []).map(Number).filter((v) => v > 0).sort((a, b) => a - b);
  state.selectedChip = Number(game.defaultBet) || BET_VALUES[0] || 1;
  const bj = game.blackjack || {};
  MAX_BET = Number(bj.maxBet) || BET_VALUES[BET_VALUES.length - 1] || Infinity;
}

/* ----------------------------------------------------------------- chips */

function renderChips() {
  els.chipRow.innerHTML = "";
  for (const v of BET_VALUES) {
    if (v > MAX_BET + 1e-9) continue;
    const chip = document.createElement("button");
    chip.className = "chip";
    chip.textContent = fmt(v);
    chip.classList.toggle("is-selected", v === state.selectedChip);
    chip.disabled = state.busy || state.inRound;
    chip.addEventListener("click", () => {
      state.selectedChip = v;
      renderChips();
      renderBet();
    });
    els.chipRow.appendChild(chip);
  }
}

function renderBet() {
  els.betValue.textContent = fmt(state.selectedChip);
}

/* ----------------------------------------------------------------- cards */

function cardEl(card) {
  const el = document.createElement("div");
  el.className = "card " + (card.color === "RED" ? "red" : "black");
  const rank = document.createElement("span");
  rank.className = "card-rank";
  rank.textContent = card.rank;
  const suit = document.createElement("span");
  suit.className = "card-suit";
  suit.textContent = card.suitSymbol;
  el.append(rank, suit);
  return el;
}

function cardBackEl() {
  const el = document.createElement("div");
  el.className = "card back";
  return el;
}

/* ----------------------------------------------------------------- rendering */

function renderEmpty() {
  els.dealerCards.innerHTML = "";
  els.dealerValue.textContent = "";
  els.playerHands.innerHTML = "";
  els.win.classList.add("is-empty");
  els.winAmount.textContent = "0.00";
  els.message.textContent = "Place your bet and deal.";
  state.inRound = false;
  showActions(["DEAL"]);
  renderChips();
}

function renderDealer(dealer) {
  els.dealerCards.innerHTML = "";
  for (const c of dealer.cards || []) els.dealerCards.appendChild(cardEl(c));
  if (dealer.hidden) els.dealerCards.appendChild(cardBackEl());
  els.dealerValue.textContent = !dealer.hidden && dealer.value != null ? `(${dealer.value})` : "";
}

function handLabel(h) {
  if (h.status === "BUST") return `Bust (${h.value})`;
  if (h.status === "BLACKJACK") return "Blackjack!";
  if (h.value === 21) return "21";
  return h.soft ? `${h.value} (soft)` : String(h.value);
}

const OUTCOME_TEXT = {
  PLAYER_BLACKJACK: "Blackjack - pays 3:2",
  WIN: "Win",
  PUSH: "Push",
  LOSE: "Lose",
};

function renderHands(resp) {
  els.playerHands.innerHTML = "";
  (resp.playerHands || []).forEach((h, i) => {
    const box = document.createElement("div");
    box.className = "bj-hand-box";
    if (resp.status !== "SETTLED" && i === resp.activeHandIndex) box.classList.add("active");
    if (h.outcome) box.classList.add("outcome-" + h.outcome.toLowerCase());

    const hand = document.createElement("div");
    hand.className = "bj-hand";
    for (const c of h.cards) hand.appendChild(cardEl(c));
    box.appendChild(hand);

    const meta = document.createElement("div");
    meta.className = "bj-hand-meta";
    const parts = [`<span class="bj-value">${handLabel(h)}</span>`, `<span class="bj-bet">${fmt(h.bet)}</span>`];
    if (h.outcome) {
      const pay = Number(h.payout || 0) > 0 ? ` +${fmt(h.payout)}` : "";
      parts.push(`<span class="bj-outcome">${OUTCOME_TEXT[h.outcome] || h.outcome}${pay}</span>`);
    }
    meta.innerHTML = parts.join(" · ");
    box.appendChild(meta);
    els.playerHands.appendChild(box);
  });
}

function showActions(available) {
  const set = new Set(available || []);
  for (const name of ACTION_BUTTONS) {
    const btn = els.actions[name];
    if (!btn) continue;
    const show = set.has(name);
    btn.classList.toggle("hidden", !show);
    btn.disabled = state.busy;
  }
  // The bet chip bar is only meaningful before a round starts (when DEAL is offered).
  els.chipBar.classList.toggle("hidden", !set.has("DEAL"));
}

function settleMessage(resp) {
  const win = Number(resp.totalWin || 0);
  const bet = Number(resp.totalBet || 0);
  let msg = win > 0 ? `Round over - you got back ${fmt(win)} ${CURRENCY}` : "Round over - no win";
  if (resp.insurance && resp.insurance.resolved) {
    msg += resp.insurance.won ? " · insurance paid" : " · insurance lost";
  }
  const net = win - bet;
  msg += ` (staked ${fmt(bet)}, net ${net >= 0 ? "+" : ""}${fmt(net)})`;
  return msg;
}

function inProgressMessage(resp) {
  if (resp.insurance == null && (resp.availableActions || []).includes("INSURANCE")) {
    return "Dealer shows an Ace - take insurance, or play on.";
  }
  const hands = resp.playerHands || [];
  if (hands.length > 1) return `Playing hand ${resp.activeHandIndex + 1} of ${hands.length}.`;
  return "Your move.";
}

function renderRound(resp) {
  state.sessionVersion = resp.sessionVersion;
  const settled = resp.status === "SETTLED";
  state.inRound = !settled;

  renderDealer(resp.dealer || { cards: [], hidden: false });
  renderHands(resp);

  if (settled) {
    const win = Number(resp.totalWin || 0);
    els.winAmount.textContent = fmt(win);
    els.win.classList.toggle("is-empty", win <= 0);
    els.message.textContent = settleMessage(resp);
    showActions(["DEAL"]);
  } else {
    els.win.classList.add("is-empty");
    els.message.textContent = inProgressMessage(resp);
    showActions(resp.availableActions);
  }

  if (typeof resp.balance === "number") {
    state.balance = Number(resp.balance);
  }
  renderHud();
  renderChips();
}

function renderHud() {
  els.balance.textContent = `${fmt(state.balance)} ${CURRENCY}`;
  els.gameState.textContent = "BLACKJACK";
  els.freeSpins.textContent = "-";
}

/* ----------------------------------------------------------------- flows */

function setBusy(busy) {
  state.busy = busy;
  for (const name of ACTION_BUTTONS) {
    const btn = els.actions[name];
    if (btn) btn.disabled = busy;
  }
  renderChips();
}

async function boot(forceNewPlayer = false) {
  try {
    setBusy(true);
    state.playerId = resolvePlayerId(forceNewPlayer);
    state.sessionId = crypto.randomUUID();
    state.token = await mintDevToken({ playerId: state.playerId, sessionId: state.sessionId });
    window.__authToken = state.token;
    // Responsible Gaming watch: the reality check is measured in elapsed time, so it has to be
    // able to fire on a player who has stopped clicking.
    if (typeof startRgWatch === "function") startRgWatch();

    const init = await api("/api/v1/blackjack/init", {
      method: "POST",
      body: { gameId: META.gameId, currency: CURRENCY },
    });
    state.sessionId = init.sessionId;
    state.sessionVersion = init.sessionVersion;
    state.balance = Number(init.balance);
    renderHud();
    logResponse("blackjack/init", init);

    if (init.activeRound) {
      renderRound(init.activeRound);
    } else {
      renderEmpty();
    }
    toast(`Demo player ${state.playerId} ready`, "success");
  } catch (e) {
    handleError("Boot failed", e);
  } finally {
    setBusy(false);
  }
}

async function deal() {
  if (state.busy || state.inRound) return;
  if (state.selectedChip > state.balance + 1e-9) {
    toast(`Not enough balance for a ${fmt(state.selectedChip)} bet`, "error");
    return;
  }
  try {
    setBusy(true);
    const resp = await api("/api/v1/blackjack/deal", {
      method: "POST",
      idempotency: true,
      body: {
        gameId: META.gameId,
        sessionId: state.sessionId,
        sessionVersion: state.sessionVersion,
        bet: state.selectedChip,
      },
    });
    logResponse("blackjack/deal", resp);
    renderRound(resp);
  } catch (e) {
    handleError("Deal failed", e);
  } finally {
    setBusy(false);
  }
}

async function sendAction(action) {
  if (state.busy) return;
  try {
    setBusy(true);
    const resp = await api("/api/v1/blackjack/action", {
      method: "POST",
      idempotency: true,
      body: {
        gameId: META.gameId,
        sessionId: state.sessionId,
        sessionVersion: state.sessionVersion,
        action,
      },
    });
    logResponse(`blackjack/action ${action}`, resp);
    renderRound(resp);
  } catch (e) {
    handleError(`${action} failed`, e);
  } finally {
    setBusy(false);
  }
}

/* ----------------------------------------------------------------- wiring */

function bindEvents() {
  els.actions.DEAL.addEventListener("click", deal);
  els.actions.HIT.addEventListener("click", () => sendAction("HIT"));
  els.actions.STAND.addEventListener("click", () => sendAction("STAND"));
  els.actions.DOUBLE.addEventListener("click", () => sendAction("DOUBLE"));
  els.actions.SPLIT.addEventListener("click", () => sendAction("SPLIT"));
  els.actions.INSURANCE.addEventListener("click", () => sendAction("INSURANCE"));
  els.resetSession.addEventListener("click", () => boot(true));
  els.clearLog.addEventListener("click", () => { const l = $("log"); if (l) l.textContent = ""; });
}

/**
 * Blackjack entry point - called by the game-page bootstrap when the resolved catalog game is BLACKJACK.
 * Shared chrome + info modal are already applied by the bootstrap.
 */
function initBlackjackGame(game) {
  Object.assign(els, {
    dealerCards: $("bjDealerCards"),
    dealerValue: $("bjDealerValue"),
    playerHands: $("bjPlayerHands"),
    win: $("blackjackWin"),
    winAmount: $("blackjackWinAmount"),
    chipBar: $("bjChipBar"),
    chipRow: $("bjChipRow"),
    betValue: $("bjBetValue"),
    message: $("bjMessage"),
    balance: $("balance"),
    gameState: $("gameState"),
    freeSpins: $("freeSpins"),
    resetSession: $("resetSession"),
    clearLog: $("clearLog"),
  });
  els.actions = {
    DEAL: $("bjDeal"),
    HIT: $("bjHit"),
    STAND: $("bjStand"),
    DOUBLE: $("bjDouble"),
    SPLIT: $("bjSplit"),
    INSURANCE: $("bjInsurance"),
  };
  applyConfig(game);
  bindEvents();
  renderChips();
  renderBet();
  renderEmpty();
  boot();
}

window.initBlackjackGame = initBlackjackGame;

})();
