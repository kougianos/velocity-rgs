# Velocity RGS

An audit-grade iGaming slot platform: a deterministic Remote Gaming Server (RGS) built with
Java 21 + Spring Boot 3.x + Postgres + Redis. Every spin outcome, feature transition, and
balance change is decided server-side.

A self-contained **browser client** (vanilla HTML/CSS/JS) ships inside the server at
[`src/main/resources/static/`](src/main/resources/static/) and is served directly by Spring
Boot at `http://localhost:8080/` in demo mode — no separate frontend build, no Node/pnpm.

> **Full local setup:** see [RUNNING_LOCALLY.md](RUNNING_LOCALLY.md).
> **Architectural blueprint:** [be-requirements.md](be-requirements.md) · [CHANGELOG.md](CHANGELOG.md)

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────┐
│         Browser client (src/.../static/)            │
│                                                     │
│  Vanilla HTML/CSS/JS — lobby + slot game UI         │
│  Pure presentation: renders server responses only   │
│  No RNG, no payline eval, no local balance math     │
└────────────────────┬────────────────────────────────┘
                     │ HTTP (REST)
                     │ Authorization: Bearer <JWT>
                     │ Idempotency-Key: <UUID>
                     │ X-Trace-Id: <UUID>
┌────────────────────▼────────────────────────────────┐
│                 Spring Boot RGS                     │
│                                                     │
│  Slot Game API  ─── Session FSM ─── Math Engine     │
│  Wallet API     ─── Audit/Replay ─── RTP Simulator  │
│                                                     │
│  Postgres (system of record: rounds, wallet, audit) │
│  Redis   (session cache, idempotency cache, locks)  │
└─────────────────────────────────────────────────────┘
```

### Core Design Decisions

| Principle | How it is enforced |
|---|---|
| Server is the single source of truth | The client never computes wins, evaluates paylines, or mutates balance locally |
| Idempotency on every mutation | `Idempotency-Key` UUID header required on all mutating endpoints; replayed unchanged on transport retry |
| Session versioning | Every request carries `sessionVersion`; stale writes fail with `SESSION_VERSION_CONFLICT` (409) |
| Strict FSM | `availableActions` array from the server drives all button state; illegal commands → `ILLEGAL_STATE_TRANSITION` (409) |
| Money safety | `BigDecimal` + `HALF_UP` rounding on the server |
| JWT in memory only | The demo client holds the token in memory; never `localStorage` |
| Deterministic replay | Every round is reconstructable bit-exact from persisted `rng_draws` via `DeterministicReplayRng` |

---

## Implementation Status

| Milestone | Status | Description |
|---|---|---|
| **M0** — Bootstrap & Cross-Cutting | ✅ Complete | Maven project, package skeleton, run-mode switches, `Money` value object, `GlobalExceptionHandler`, idempotency aspect, MDC correlation filter, JWT auth filter, Logback JSON, Micrometer |
| **M1** — Math Domain & JSON Config | ✅ Complete | `Symbol`, `Payline`, `PayTable`, `ReelStrip` records; `SlotMathLoader` from `math/<game>/v1.json`; `ReelEvaluator` with WILD substitution, payline evaluation, max-win cap |
| **M2** — RNG Engine & Grid | ✅ Complete | `SecureRandomNumberGenerator`, `DeterministicReplayRng`, `GridGenerationEngine` with reel-strip sets (BASE / POWER_BET / FREE_SPINS) |
| **M3** — Wallet | ✅ Complete | `WalletGateway` interface, `InternalWalletGateway` (demo/wallet-internal), `OperatorWalletGateway` skeleton; `WalletController` with `authenticate`, `balance`, `debit`, `credit`, `rollback`; idempotent ledger; demo seeder |
| **M4** — Session FSM & Persistence | ✅ Complete | `GameSession` JPA entity with optimistic `@Version`; sealed `SessionState` / `SessionCommand` types; `SessionStateMachine` pure function; Redis session cache + TTL; `PlayerActionLock` |
| **M5** — Slot Game API (end-to-end) | ✅ Complete | `SlotGameController` (`/init`, `/spin`, `/feature/start`, `/feature/buy`, `/feature/pick`); `SlotEngineService` orchestrator; `PickCollectEngine`; `BonusBuyPolicyService`; full saga (debit → evaluate → credit → rollback on failure); `RtpSimulator` CLI |
| **M6** — Audit, Replay & Reconciliation | ✅ Complete | Bit-exact `ReplayService` via `DeterministicReplayRng`; `ReconciliationJob` (hourly, bucket-based); `OperatorWalletGateway` (WebClient, WireMock contract tests); per-pick state-hash audit events |
| **M7** — QA Readiness & Operational Tooling | ✅ Complete | `DevTokenController` (demo profile); `AdminQaController` (set balance, get session, get round); `SimulatorAdminController` with `RtpSimulationService`; `V9` Flyway migration for `audit_simulation_run`; actuator hardening for `wallet-operator` profile |

---

## Repository Layout

```
velocity-rgs/
├── docker-compose.yml          # Postgres 16 + Redis 7 (local infra)
├── RUNNING_LOCALLY.md          # Step-by-step local setup guide
├── README.md
├── CHANGELOG.md                # Milestone history
├── be-requirements.md          # Full architectural blueprint (normative)
├── pom.xml
└── src/
    ├── main/
    │   ├── java/com/velocity/rgs/
    │   │   ├── config/         # Security, Jackson, OpenAPI, virtual threads
    │   │   ├── common/         # error/, idempotency/, money/
    │   │   ├── math/           # config/, domain/, engine/
    │   │   ├── rng/            # SecureRandomNumberGenerator, RngDraw
    │   │   ├── session/        # domain/, fsm/, persistence/, service/
    │   │   ├── game/           # api/, service/, feature/{freespins,bonusbuy,pickcollect}/
    │   │   ├── wallet/         # api/, service/, gateway/, domain/, persistence/
    │   │   ├── audit/          # replay/, reconciliation/, pickaudit/, simulation/
    │   │   ├── qa/             # admin/, dev/, simulator/ (demo mode only)
    │   │   └── observability/  # MDC filter, metrics
    │   └── resources/
    │       ├── application.yml             # Single config file (run-mode switches)
    │       ├── math/{aztec-fire,frost-crown,inferno-riches}/v1.json
    │       ├── db/migration/               # Flyway V1–V9
    │       └── static/                     # Built-in browser client (HTML/CSS/JS)
    └── test/                   # Unit + Testcontainers integration tests
```

---

## Games & Features

Three slots ship as JSON game configs under `src/main/resources/games/`
(`aztec-fire`, `frost-crown`, `inferno-riches`) — each file holds both a `presentation` block
(title, theme, copy, symbol glyphs) and a `math` block (grid, paylines, pay table, reel strips), so
a game is fully described in one place. The built-in client exposes them via a lobby and renders each
game purely from the server catalog (`GET /api/v1/games`).
Each is a 3×5, 20-payline slot, fully playable end-to-end with these mechanics:

| Feature | Entry path | Server mechanic |
|---|---|---|
| **Base Game Spin** | Spin button | Reel grid generated from `BASE` strips, payline eval, debit + optional credit |
| **Free Spins** | 3+ Scatter symbols (organic) or Bonus Buy | Organic trigger awards 10 free spins on high-RTP `FREE_SPINS` reel strips; re-triggers add 5 spins; single accumulated credit on settlement |
| **Power Bet** | Toggle in HUD | Sends `powerBetActive=true`; server switches to `POWER_BET` reel strips; bet multiplied by 1.5× |
| **Bonus Buy** | Buy panel | `FREE_SPINS_BUY` only; an industry-standard 12-spin feature priced by volatility — Frost 80× / Aztec 100× / Inferno 150× bet. The bought round is made *richer per spin* (not longer) via a per-game `freeSpinsWinMultiplier` applied to the feature win at settlement, calibrated so the buy returns the game's 96% RTP; organic free spins are unaffected. Server debit → saga entry (Pick & Collect is no longer buyable) |
| **Pick & Collect** | Organic in-spin trigger (`~1 in triggerOneInN`) | Deterministic 12-tile board generated at feature start; CREDITS / MULTIPLIER / COLLECT / END tiles; keep picking until an END tile forfeits the unbanked pot (only COLLECT-banked wins survive); single credit on completion |

---

## Run Modes

All configuration lives in a single [`src/main/resources/application.yml`](src/main/resources/application.yml),
driven by two switches (no Spring profiles). Set them in the file or override with `-Drgs.mode=…`
/ env vars.

| Switch | Values | Effect |
|---|---|---|
| `rgs.mode` | `demo` (default) / `production` | `demo`: dev-token + admin QA + simulator HTTP endpoints registered, demo wallet auto-seeding, built-in client + swagger/actuator anonymous. `production`: helpers off, only health probes public. |
| `rgs.wallet.mode` | `internal` (default) / `operator` | `internal`: in-process seeded wallet, no external calls. `operator`: external WebClient → `RGS_WALLET_OPERATOR_URL`. |
| `rgs.simulator.cli-enabled` | `false` (default) / `true` | When `true`, runs one batch RTP simulation on startup. |

The `test` Spring profile remains for the integration-test harness only (Testcontainers,
deterministic JWT secret); it inherits demo + internal defaults.

## Environment Variables

| Variable | Default | Purpose |
|---|---|---|
| `RGS_DB_URL` | `jdbc:postgresql://localhost:5432/rgs` | Postgres JDBC URL |
| `RGS_DB_USERNAME` | `rgs` | Postgres username |
| `RGS_DB_PASSWORD` | `rgs` | Postgres password |
| `RGS_REDIS_HOST` | `localhost` | Redis host |
| `RGS_REDIS_PORT` | `6379` | Redis port |
| `RGS_JWT_SECRET` | dev-only placeholder | HS256 signing secret — **override in any non-demo deployment** |
| `RGS_MODE` | `demo` | Run mode: `demo` (QA helpers + relaxed auth) or `production` |
| `RGS_WALLET_MODE` | `internal` | Wallet backend: `internal` (in-process) or `operator` (external HTTP) |
| `RGS_WALLET_OPERATOR_URL` | `http://localhost:9090` | External wallet base URL (only when `RGS_WALLET_MODE=operator`) |
| `RGS_WALLET_OPERATOR_TOKEN` | — | Optional static bearer token for the operator wallet |

## Build & Test

```bash
docker compose up -d   # Postgres 16 + Redis 7
mvn -B verify          # compile + all tests (Testcontainers Postgres + Redis)
mvn -B package         # build runnable jar
mvn spring-boot:run    # run locally (demo mode) → http://localhost:8080/
```

### QA helpers (demo mode only)

- `POST /api/v1/dev/token` — mint a JWT (no auth required)
- `POST /api/v1/admin/wallet/balance` — set arbitrary player balance (ADMIN role)
- `GET /api/v1/admin/session/{playerId}` — inspect persistent + cached session state (ADMIN role)
- `GET /api/v1/admin/round/{roundId}` — inspect persisted round including `rng_draws` (ADMIN role)
- `POST /api/v1/admin/simulator/run` — synchronous RTP simulation, persists an `audit_simulation_run` row (ADMIN role)

## Key URLs (demo mode)

| URL | Purpose |
|---|---|
| `http://localhost:8080/` | Built-in browser client (lobby + slot game) |
| `http://localhost:8080/swagger-ui.html` | Swagger UI (all endpoints) |
| `http://localhost:8080/v3/api-docs` | Raw OpenAPI spec |
| `http://localhost:8080/actuator/health` | Server health check |
| `http://localhost:8080/actuator/prometheus` | Prometheus metrics |
