# Velocity RGS

An audit-grade iGaming platform: a deterministic Remote Gaming Server (RGS) built with
Java 21 + Spring Boot 3.x + Postgres + Redis. Every outcome, state transition, and balance
change is decided server-side.

It hosts three game categories - **slots**, **roulette**, and **blackjack** - behind one
unified game catalog and one wallet.

A self-contained **browser client** (vanilla HTML/CSS/JS) ships inside the server at
[`src/main/resources/static/`](src/main/resources/static/) and is served directly by Spring
Boot at `http://localhost:8080/` in demo mode - no separate frontend build, no Node/pnpm.

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────┐
│         Browser client (src/.../static/)            │
│                                                     │
│  Vanilla HTML/CSS/JS - lobby + per-game UIs          │
│  Pure presentation: renders server responses only   │
│  No RNG, no outcome eval, no local balance math     │
└────────────────────┬────────────────────────────────┘
                     │ HTTP (REST)
                     │ Authorization: Bearer <JWT>
                     │ Idempotency-Key: <UUID>
                     │ X-Trace-Id: <UUID>
┌────────────────────▼────────────────────────────────┐
│                 Spring Boot RGS                     │
│                                                     │
│  Slot · Roulette · Blackjack APIs                   │
│  Session FSM ── Game Engines ── Wallet              │
│  Audit / Replay ── RTP Simulator                    │
│                                                     │
│  Postgres (system of record: rounds, wallet, audit) │
│  Redis   (session cache, idempotency cache, locks)  │
└─────────────────────────────────────────────────────┘
```

### Core Design Decisions

| Principle | How it is enforced |
|---|---|
| Server is the single source of truth | The client never computes wins, evaluates outcomes, or mutates balance locally |
| Idempotency on every mutation | `Idempotency-Key` UUID header required on all mutating endpoints; replayed unchanged on transport retry |
| Session versioning | Every request carries `sessionVersion`; stale writes fail with `SESSION_VERSION_CONFLICT` (409) |
| Strict FSM | `availableActions` from the server drives all button state; illegal commands → `ILLEGAL_STATE_TRANSITION` (409) |
| Money safety | `BigDecimal` + `HALF_UP` rounding on the server |
| JWT in memory only | The demo client holds the token in memory; never `localStorage` |
| Deterministic replay | A round is reconstructable bit-exact from persisted RNG draws, or the server says why not - see [the caveat](#which-rounds-cannot-be-replayed-yet) |

---

## Games

| Game | Type | Flow | Notes |
|---|---|---|---|
| Aztec Fire, Frost Crown, Inferno Riches | **Slot** | single-step spin | Fixed paylines. Distinct grids (4/5/6 reels), free spins, bonus-buy, pick & collect; base RTP 96% |
| Jade Tiger | **Slot** | single-step spin | **243 ways** (no paylines) on a 5x3 grid - every left-to-right path pays. Free spins, pick & collect; base RTP 96% |
| Gilded Cascade | **Slot** | multi-drop spin | **Cascading reels**: wins are cleared and refilled, each tumble in the same spin paying at a higher multiplier (1x/2x/3x/5x/10x). Expanding wilds in free spins; base RTP 96% |
| Dragon Hoard | **Slot** | stateful multi-step | **Hold & Spin**: 5+ coins lock and award 3 respins, every catch resetting the counter; MINI/MINOR/MAJOR/GRAND jackpot tiers, GRAND on a full grid. Purchasable (`HOLD_SPIN_BUY`, ×230.76). Sticky + walking wilds in free spins; base RTP 96% |
| European Roulette | **Roulette** | single-step spin | Server-authoritative single-zero wheel, exact 36/37 payout math |
| Classic Blackjack | **Blackjack** | stateful multi-step | 6-deck S17, 3:2, DAS, splits, insurance; round state persisted across deal/action calls |

All three categories are exposed through one catalog at `GET /api/v1/games`, tagged by `gameType`. Slots
also report their `winModel` (`PAYLINES` / `WAYS`) and a display `winModelLabel` so the lobby can badge
"243 Ways" without inspecting a spin.

### Slot mechanics, all config-driven

Each mechanic is a block in `games/<gameId>/<mathVersion>.json`; absent means off, so every game authored
before a mechanic existed keeps its behaviour and its calibrated RTP untouched.

| Block | Mechanic | Engine |
|---|---|---|
| `winModel` | Fixed paylines vs. 243 ways | `PaylineWinEvaluator` / `WaysWinEvaluator` behind `ReelEvaluator` |
| `waysDirection` | `LEFT_TO_RIGHT` or `BOTH_WAYS` (win-both-ways) | `WaysWinEvaluator` |
| `cascades` | Tumbling reels + progressive per-drop multiplier ladder | `CascadeEngine` + `GridGenerationEngine.refill` |
| `respins` | Hold & Spin: coin lock, reset-on-catch counter, jackpot tiers | `RespinEngine`, FSM states `RESPIN_AWAITING` / `RESPIN_LOOP` |
| `wildFeatures` | Expanding / sticky / walking wilds, scoped per strip set | `WildFeatureEngine` (a grid transform, applied pre-evaluation). Sticky/walking wilds record their carry per round in `feature_context`, so they replay - see below |

A cascading round is persisted as its **whole drop sequence** - `game_round.matrix` and `stop_positions`
hold one entry per drop - and every refill draws through the round's own `RngDrawSink`, so the tumble
replays bit-exact from `rng_draws` like any other round.

A Hold &amp; Spin respin is a different *kind* of round: it re-draws only the unlocked cells, so its draws
mean nothing without the coins held going in. `game_round.round_kind` discriminates the two and
`feature_context` carries that input state, which is what lets a respin replay independently too.
The same column carries the wilds a sticky/walking free spin inherited from the spin before it, for the
same reason. Rounds written before either capture existed refuse replay with a reason rather than
failing.

### What the lobby advertises, and why it cannot lie

Every game card in the lobby carries an **Info** button opening a per-game sheet whose *Features* tab
lists the mechanics that game actually ships - the cascade ladder, the Hold & Spin trigger and jackpot
tiers, the wild behaviours, the buy prices.

None of it is marketing copy kept in step by hand. `GameFeatureFactory` derives the list from the same
`math` blocks that switch each mechanic on, and every number it quotes is read out of that block. A
card exists **iff** the mechanic is configured, so deleting `math.cascades` removes "Cascading Reels"
from the lobby in the same change that stops the reels tumbling.
[`GameFeatureFactoryTest`](src/test/java/com/velocity/rgs/catalog/GameFeatureFactoryTest.java) asserts
that biconditional over every shipped slot, in both directions.

The sheet is one component in two shapes: a centered dialog on desktop, and on phones a bottom sheet
that is dragged away with the thumb that opened it (distance- **or** velocity-dismissed, with the
scrolling body keeping its own gesture). The same cards render inside the in-game info modal, so a
player sees one description of a mechanic wherever they meet it.

### Replaying a round from the UI

The **Round History** page (`/history.html`) lists every persisted round with a **Replay** button. It
calls `POST /api/v1/admin/replay/{roundId}`, which re-runs the recorded RNG draws through the same
engine and reports whether the reconstruction matched. A cascading round shows every drop side by side
with its multiplier, its win and the draws that produced it - which is the audit story the replay
infrastructure exists for, made visible.

### Sharing that proof with someone who has no account

Every history row also carries **Share proof**, which mints a signed, expiring link -
`/r/<token>` - to that one round. Opening it needs no login, no session and no local database: the
[public replay page](src/main/resources/static/replay.js) rebuilds the round from its persisted draws,
plays the drops back with the game's own symbols, and prints the raw `rng_draws` underneath so the
claim can be checked rather than taken.

The token is the whole authorisation, and it is deliberately narrow:

| Property | How |
|---|---|
| Scoped to one round | The round id is the token's `sub` and the endpoint accepts **no** round parameter, so there is no request field to repoint at another round |
| Not a player credential | Signed with a key derived from `rgs.security.jwt-secret` for this purpose alone, so it fails verification in `JwtAuthenticationFilter` - and a player's JWT fails verification as a link |
| Anonymous | `PublicRoundReplay` is a redacted projection: no `playerId`, no `sessionId` |
| Expiring | `rgs.replay.public-link-ttl` (default 24h). Links are stateless, so outstanding ones are revoked by rotating the secret, not individually |

Minting replays the round first and returns that verdict with the link, so a link only ever exists for
a round that reconstructs - you cannot hand someone a URL that fails in front of them. The page then
re-runs the reconstruction when it is opened; nothing on it is a cached verdict.

Expired and tampered links are answered with their own codes (`REPLAY_LINK_EXPIRED` → 410,
`REPLAY_LINK_INVALID` → 400) and their own pages, because a public URL is the one surface a stranger
meets in its broken state and "this ran out" is a different thing to say than "this was edited".

### Rounds that depend on the spin before them

Some rounds are not fully explained by their own draws, and each is handled the same way: whatever the
round needs as *input* is written to `game_round.feature_context` when it is played, and read back on
replay.

- **Hold & Spin respins** re-draw only the unlocked cells, so the replay needs the coins held going in.
- **Sticky and walking wilds** (`math.wildFeatures.sticky` / `.walking`, today Dragon Hoard's free
  spins) stamp wilds held over from the previous spin - a walking one a reel further left each time.
  The carry lives on the *session* while the feature runs, so the round records its own copy.
- **The spin that settles a free-spins feature** pays the whole feature, not its own board: every
  earlier spin's win accumulated on the session, times the boost a bonus buy attached at purchase. It
  records both running totals, so the payout is rebuilt as the sum it actually was. Without that, the
  replay compared one spin's lines against a whole feature's payout and reported a divergence on a
  round where the board had matched perfectly - the public page shows the sum term by term now, because
  a board paying €6.88 next to a €655.66 headline should be explained rather than asserted.
- **Expanding wilds** need nothing: the transform is a pure function of the drawn grid, so
  `ReplayService` re-applies it. Gilded Cascade's free spins rely on this.

The carry is written **even when it is empty**, and that is the point: an empty carry and an
unrecorded one produce the same board and are not the same claim. A `NULL` therefore means only one
thing - the round predates the capture - and those rounds are refused with `ROUND_NOT_REPLAYABLE`
(409) and a stated reason rather than replayed as though they had started clean, which after the first
spin of a feature they had not. The server declines to claim a proof it cannot guarantee, which is what
keeps `INTERNAL_ERROR` from the replay path meaning what it should: the engine genuinely diverged.

[`WildFeatureReplayIntegrationTest`](src/test/java/com/velocity/rgs/audit/replay/WildFeatureReplayIntegrationTest.java)
pins all of it, including that tampering with a recorded carry breaks the reconstruction - without
that, the suite would pass just as happily if `ReplayService` ignored `feature_context` outright.
The public replay page marks the carried cells on the board and says how many were inherited, so the
one thing that did not come out of this round's draws is visible rather than asserted.

The settlement case is a reminder of how a test suite hides a bug: the free-spins cover filtered rounds
by `stateContext == FREE_SPINS_LOOP`, which is the state a round leaves the session in - and the spin
that settles a feature leaves it in `BASE_GAME`. The one round whose payout was wrong was the one round
the suite could not see. It is now selected the way `ReplayService` selects it, by the absence of a bet
debit.

---

## Repository Layout

The whole project is **one Spring Boot module rooted at the repo root**. Shared/platform code
lives at the top of the package tree; each game category has its own sub-package.

```
velocity-rgs/
├── docker-compose.yml          # Postgres 16 + Redis 7 (local infra)
├── Dockerfile
├── README.md
├── pom.xml
└── src/
    ├── main/
    │   ├── java/com/velocity/rgs/
    │   │   ├── slot/          # Slot game: api/, service/, math/, fsm/, feature/{bonusbuy,pickcollect,respin}/
    │   │   ├── roulette/      # European roulette: config/, engine/, service/, api/
    │   │   ├── blackjack/     # Classic blackjack: config/, engine/, service/, api/
    │   │   ├── card/          # Reusable card primitives (Suit/Rank/Card/Shoe/HandValue)
    │   │   ├── catalog/       # Unified game catalog + shared GameInfo/BetConfig
    │   │   ├── session/       # Shared session store, versioning, Redis cache, per-player lock
    │   │   ├── wallet/        # Balance/debit/credit/rollback (internal + operator gateway)
    │   │   ├── rng/           # RNG + deterministic replay seeds
    │   │   ├── audit/         # replay/, reconciliation/, pickaudit/, simulation/
    │   │   ├── qa/            # admin/, dev/, simulator/ (demo mode only)
    │   │   ├── config/        # Security, Jackson, OpenAPI, virtual threads
    │   │   ├── common/        # error/, idempotency/, money/
    │   │   └── observability/ # MDC trace filter, metrics
    │   └── resources/
    │       ├── application.yml             # Single config file (run-mode switches)
    │       ├── games/{aztec-fire,frost-crown,inferno-riches,jade-tiger,gilded-cascade,
    │       │          dragon-hoard,european-roulette,classic-blackjack}/v1.json
    │       ├── db/migration/               # Flyway V1–V11
    │       └── static/                     # Built-in browser client (HTML/CSS/JS)
    └── test/                   # Unit + Testcontainers integration tests
```

---

## API

```
GET  /api/v1/games                                   # unified catalog (slot + roulette + blackjack)

POST /api/v1/slot/{init,spin}
POST /api/v1/slot/feature/{start,buy,pick}

POST /api/v1/roulette/{init,spin}

POST /api/v1/blackjack/{init,deal,action}            # stateful: round state persisted between calls

GET  /api/v1/wallet/balance
POST /api/v1/wallet/{authenticate,debit,credit,rollback}

GET  /api/v1/public/replay/{token}                    # anonymous: one round, from a signed link
POST /api/v1/admin/replay/{roundId}/share             # mint that link (ADMIN)

POST /api/v1/dev/token                               # demo mode
GET  /api/v1/admin/*                                 # demo mode: sessions, rounds, replay
POST /api/v1/admin/*                                 # demo mode: set balance, run simulator
```

`/api/v1/public/**` is anonymous in **every** run mode, not just demo - a proof link that only worked
on a laptop would not be worth minting.

---

## Build & Test

```bash
docker compose up -d   # Postgres 16 + Redis 7
mvn -B verify          # compile + tests (Testcontainers Postgres + Redis)
mvn -B package         # build runnable jar
mvn spring-boot:run    # run locally (demo mode) → http://localhost:8080/
```

### Game math tests

The statistical tests are tagged `slow` and excluded from `verify` (they simulate millions of
rounds). They split by intent:

```bash
mvn -Prtp test         # guards: assert each game converges to its declared RTP. Run in CI.
mvn -Pcalibrate test   # design aids: print the constants you feed back into the game JSON.
```

`-Prtp` is the regression net for game math and runs on every change to `games/**` or the
engines, plus nightly ([`.github/workflows/rtp.yml`](.github/workflows/rtp.yml)). `-Pcalibrate`
asserts nothing and is deliberately kept out of CI.

Beyond RTP convergence, `-Prtp` also runs `GameStatisticsVerificationTest`, which asserts the
**hit frequency each game declares on its own spec sheet** (`presentation.info.specs`) against the
simulator, plus the invariant that no sampled round exceeds `limits.maxWinPerRoundMultiplier`. RTP
alone says nothing about either - two games can converge to 96% with completely different volatility.

For a game whose RTP is dominated by a rare, rich feature, calibrate with `CascadeCalibrationHarness`
rather than by eye: it separates the pay-table-driven share of RTP from the flat, credit-denominated
features (Pick & Collect, Hold & Spin) and prints the exact pay-table scale, because scaling the table
moves only the former.
