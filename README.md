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
| Deterministic replay | Every round is reconstructable bit-exact from persisted RNG draws |

---

## Games

| Game | Type | Flow | Notes |
|---|---|---|---|
| Aztec Fire, Frost Crown, Inferno Riches | **Slot** | single-step spin | Fixed paylines. Distinct grids (4/5/6 reels), free spins, bonus-buy, pick & collect; base RTP 96% |
| Jade Tiger | **Slot** | single-step spin | **243 ways** (no paylines) on a 5x3 grid - every left-to-right path pays. Free spins, pick & collect; base RTP 96% |
| European Roulette | **Roulette** | single-step spin | Server-authoritative single-zero wheel, exact 36/37 payout math |
| Classic Blackjack | **Blackjack** | stateful multi-step | 6-deck S17, 3:2, DAS, splits, insurance; round state persisted across deal/action calls |

All three are exposed through one catalog at `GET /api/v1/games`, tagged by `gameType`.

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
    │   │   ├── slot/          # Slot game: api/, service/, math/, fsm/, feature/{bonusbuy,pickcollect}/
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
    │       ├── games/{aztec-fire,frost-crown,inferno-riches,jade-tiger,european-roulette,classic-blackjack}/v1.json
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

POST /api/v1/dev/token                               # demo mode
GET  /api/v1/admin/*                                 # demo mode: sessions, rounds, replay
POST /api/v1/admin/*                                 # demo mode: set balance, run simulator
```

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
