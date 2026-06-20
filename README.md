# Velocity RGS

A full-stack, audit-grade iGaming slot platform. The **server** is a deterministic Remote Gaming Server (RGS) built with Java 21 + Spring Boot 3.x + Postgres + Redis. The **client** is a React 18 + PixiJS v8 slot game renderer that acts as a pure thin presentation tier — every spin outcome, feature transition, and balance change is decided server-side.

> **Full local setup:** see [RUNNING_LOCALLY.md](RUNNING_LOCALLY.md).  
> **Backend blueprint:** [server/be-requirements.md](server/be-requirements.md) · [server/CHANGELOG.md](server/CHANGELOG.md)  
> **Client blueprint:** [client/client-requirements.md](client/client-requirements.md) · [client/CHANGELOG.md](client/CHANGELOG.md)

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────┐
│                   Browser (client/)                 │
│                                                     │
│  React HUD (balance, buttons, modals, toasts)       │
│  PixiJS canvas (reels, win animations, pick board)  │
│                                                     │
│  Strict rules: no RNG, no payline eval, no balance  │
│  math — all visual derivatives of server responses  │
└────────────────────┬────────────────────────────────┘
                     │ HTTP (REST)
                     │ Authorization: Bearer <JWT>
                     │ Idempotency-Key: <UUID>
                     │ X-Trace-Id: <UUID>
┌────────────────────▼────────────────────────────────┐
│               Spring Boot RGS (server/)              │
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
| Server is the single source of truth | Client never computes wins, evaluates paylines, or mutates balance locally |
| Idempotency on every mutation | `Idempotency-Key` UUID header required on all mutating endpoints; replayed unchanged on transport retry |
| Session versioning | Every request carries `sessionVersion`; stale writes fail with `SESSION_VERSION_CONFLICT` (409) |
| Strict FSM | `availableActions` array from the server drives all button state; illegal commands → `ILLEGAL_STATE_TRANSITION` (409) |
| Money safety | `BigDecimal` + `HALF_UP` rounding on the server; `decimal.js-light` on the client |
| JWT in memory only | Never `localStorage`; `sessionStorage` only in demo mode |
| Deterministic animations | Pixi animates from server response fields only — `Math.random()` banned in animator code |

---

## Implementation Status

### Server Milestones

| Milestone | Status | Description |
|---|---|---|
| **M0** — Bootstrap & Cross-Cutting | ✅ Complete | Maven project, package skeleton, Spring profiles, `Money` value object, `GlobalExceptionHandler`, idempotency aspect, MDC correlation filter, JWT auth filter, Logback JSON, Micrometer |
| **M1** — Math Domain & JSON Config | ✅ Complete | `Symbol`, `Payline`, `PayTable`, `ReelStrip` records; `SlotMathLoader` from `math/aztec-fire/v1.json`; `ReelEvaluator` with WILD substitution, payline evaluation, max-win cap |
| **M2** — RNG Engine & Grid | ✅ Complete | `SecureRandomNumberGenerator`, `DeterministicReplayRng`, `GridGenerationEngine` with reel-strip sets (BASE / POWER_BET / FREE_SPINS) |
| **M3** — Wallet | ✅ Complete | `WalletGateway` interface, `InternalWalletGateway` (demo/wallet-internal), `OperatorWalletGateway` skeleton; `WalletController` with `authenticate`, `balance`, `debit`, `credit`, `rollback`; idempotent ledger; demo seeder |
| **M4** — Session FSM & Persistence | ✅ Complete | `GameSession` JPA entity with optimistic `@Version`; sealed `SessionState` / `SessionCommand` types; `SessionStateMachine` pure function; Redis session cache + TTL; `PlayerActionLock` |
| **M5** — Slot Game API (end-to-end) | ✅ Complete | `SlotGameController` (`/init`, `/spin`, `/feature/start`, `/feature/buy`, `/feature/pick`); `SlotEngineService` orchestrator; `PickCollectEngine`; `BonusBuyPolicyService`; full saga (debit → evaluate → credit → rollback on failure); `RtpSimulator` CLI |
| **M6** — Audit, Replay & Reconciliation | ✅ Complete | Bit-exact `ReplayService` via `DeterministicReplayRng`; `ReconciliationJob` (hourly, bucket-based); `OperatorWalletGateway` (WebClient, WireMock contract tests); per-pick state-hash audit events |
| **M7** — QA Readiness & Operational Tooling | ✅ Complete | `DevTokenController` (demo profile); `AdminQaController` (set balance, get session, get round); `SimulatorAdminController` with `RtpSimulationService`; `V9` Flyway migration for `audit_simulation_run`; actuator hardening for `wallet-operator` profile |

### Client Milestones

| Milestone | Status | Description |
|---|---|---|
| **M0** — Bootstrap & Tooling | ✅ Complete | Vite + React 18 + TypeScript strict, Vitest, Playwright, MSW v2, ESLint with `no-restricted-paths`, Husky/lint-staged, `pnpm verify` |
| **M1** — API Layer & Generated Types | ✅ Complete | `openapi-typescript` generation, typed axios instance (`X-Trace-Id` interceptor), `RgsHttpError` / `RgsNetworkError`, wrappers for all slot / wallet / admin / dev endpoints, `Money` value object, `newIdempotencyKey()` |
| **M2** — Auth, Session Init & FSM Mirror | ✅ Complete | `authStore` (memory / sessionStorage), dev-token panel, JWT decode, `sessionStore` read-only mirror, `useSessionInit`, `useSessionRecovery`, route guards (`RequireAuth`, `RequireRole`) |
| **M3** — Wallet Panel & Balance Feed | ✅ Complete | `walletStore`, `useWalletBalance` (React Query, pause-on-mutation), `BalancePanel` with framer-motion pulse, `BetSelector` with bet ladder |
| **M4** — Pixi Stage & Reel Rendering | ✅ Complete | `PixiApp` lifecycle, `usePixiApp`, `assets.ts` texture loader, `Reel`, `SlotGrid`, `SlotStage` rendering a server-supplied matrix |
| **M5** — Base Game Spin Loop | ✅ Complete | `useSpin` mutation, `SpinButton`, `SpinAnimator` (deterministic, staggered reels, win-line overlays), reason-code banners, latency feedback (250 ms spinner, 1.5 s "Still working…") |
| **M6** — Free Spins UI & Power Bet | ✅ Complete | `FreeSpinsOverlay`, `useStartFeature`, retrigger "+N" burst, settlement counter tween, `PowerBetToggle` |
| **M7** — Bonus Buy & Pick & Collect | ✅ Complete | `BonusBuyPanel` (confirmation modal, affordability check), `useBuyFeature`, `PickBoard` (Pixi), `PickBoardScene`, `useFeaturePick`, resume from `activeFeatureView` on page reload |
| **M8** — QA / Admin Tooling | ✅ Complete | `/admin` route (Wallet / Session / Round / Replay / Simulator tabs), `client/http/velocity-rgs-client.http` |
| **M9** — Observability, A11y, Perf | ✅ Complete | Structured `logger` with trace enrichment, optional `sendBeacon` sink, `web-vitals` (LCP/INP/CLS), collapsible `DebugHud`, `SpinAnnouncer` live-region for screen-readers, lazy-loaded admin chunk, Pixi manual chunk |

---

## Repository Layout

```
velocity-rgs/
├── docker-compose.yml          # Postgres 16 + Redis 7 (local infra)
├── RUNNING_LOCALLY.md          # Step-by-step local setup guide
├── server/                     # Spring Boot RGS service
│   ├── be-requirements.md      # Full backend architectural blueprint
│   ├── CHANGELOG.md
│   ├── README.md
│   ├── pom.xml
│   ├── http/                   # VS Code REST Client request collection
│   │   └── velocity-rgs.http
│   └── src/
│       └── main/
│           ├── java/com/velocity/rgs/
│           │   ├── config/         # Security, Jackson, OpenAPI, virtual threads
│           │   ├── common/         # error/, idempotency/, money/
│           │   ├── math/           # config/, domain/, engine/
│           │   ├── rng/            # SecureRandomNumberGenerator, RngDraw
│           │   ├── session/        # domain/, fsm/, persistence/, service/
│           │   ├── game/           # api/, service/, feature/{freespins,bonusbuy,pickcollect}/
│           │   ├── wallet/         # api/, service/, gateway/, domain/, persistence/
│           │   ├── audit/          # replay/, reconciliation/, pickaudit/, simulation/
│           │   ├── qa/             # admin/, dev/, simulator/ (demo profile only)
│           │   └── observability/  # MDC filter, metrics
│           └── resources/
│               ├── math/aztec-fire/v1.json   # Slot math config
│               └── db/migration/             # Flyway V1–V9
└── client/                     # React + PixiJS slot client
    ├── client-requirements.md  # Full client architectural blueprint
    ├── CHANGELOG.md
    ├── openapi/                 # openapi.yaml (mirrored from server) + gap-report.md
    ├── http/                    # VS Code REST Client (client-side flows)
    │   └── velocity-rgs-client.http
    ├── .env.example
    └── src/
        ├── api/                 # generated/, http/, slot/, wallet/, admin/, dev/, enums.ts
        ├── app/                 # App router, query client
        ├── auth/                # authStore, AuthPage, JWT helpers
        ├── session/             # sessionStore, useSessionInit, useSessionRecovery
        ├── wallet/              # walletStore, BalancePanel, BetSelector
        ├── game/
        │   ├── pixi/            # PixiApp, Reel, SlotGrid, SlotStage, SpinAnimator
        │   ├── spin/            # useSpin
        │   ├── ui/              # SpinButton, PowerBetToggle, banners
        │   ├── feature/         # freespins/, bonusbuy/, pickcollect/
        │   └── math/            # aztec-fire.ts client-side math mirror
        ├── common/              # money/, idempotency/, ids/
        ├── observability/       # logger, DebugHud, webVitals
        ├── pages/               # PlayPage, AuthPage, AdminPage, NotFoundPage
        └── ui/                  # Toast, Modal, Spinner primitives
```

---

## Game Features Implemented

The `aztec-fire` slot (3×5 grid, 20 paylines) is fully playable end-to-end:

| Feature | Entry path | Server mechanic |
|---|---|---|
| **Base Game Spin** | Spin button | Reel grid generated from `BASE` strips, payline eval, debit + optional credit |
| **Free Spins** | 3+ Scatter symbols or Bonus Buy | `FREE_SPINS_BUY` (80× bet); 10 free spins on high-RTP `FREE_SPINS` reel strips; re-triggers add 5 spins; single accumulated credit on settlement |
| **Power Bet** | Toggle in HUD | Sends `powerBetActive=true`; server switches to `POWER_BET` reel strips; bet multiplied by 1.5× |
| **Bonus Buy** | Buy panel | `FREE_SPINS_BUY` (80× bet) or `PICK_COLLECT_BUY` (120× bet); server debit → saga entry |
| **Pick & Collect** | Natural trigger or Bonus Buy | Deterministic 12-tile board generated at feature start; CREDITS / MULTIPLIER / COLLECT / BLANK tiles; 5 picks; single credit on completion |

---

## Server Environment Variables

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

## Client Environment Variables

| Variable | Required | Default | Purpose |
|---|---|---|---|
| `VITE_API_BASE_URL` | yes | — | RGS server base URL, e.g. `http://localhost:8080` |
| `VITE_DEFAULT_GAME_ID` | yes | — | Game identifier, e.g. `aztec-fire` |
| `VITE_DEFAULT_CURRENCY` | yes | — | `EUR` or `USD` |
| `VITE_BET_LADDER` | no | `0.20,0.50,1.00,2.00,5.00,10.00` | Comma-separated bet options |
| `VITE_ENABLE_DEV_TOKEN` | no | `false` | Show the dev-token login panel |
| `VITE_ENABLE_MSW` | no | `false` | Use MSW mocks instead of the live server |
| `VITE_ENABLE_DEBUG_HUD` | no | `false` | Collapsible debug HUD with recent trace IDs |
| `VITE_AUTH_STORAGE` | no | `memory` | `memory` or `session` — `localStorage` is forbidden |
| `VITE_LOG_SINK_URL` | no | — | Optional remote log sink (via `sendBeacon`) |
| `VITE_WALLET_REFRESH_MS` | no | `30000` | Balance refetch interval (ms) |

## Run Modes

All configuration lives in a single `server/src/main/resources/application.yml`, driven
by two switches (no Spring profiles). Set them in the file or override with `-Drgs.mode=…`
/ env vars.

| Switch | Values | Effect |
|---|---|---|
| `rgs.mode` | `demo` (default) / `production` | `demo`: dev-token + admin QA + simulator HTTP endpoints registered, demo wallet auto-seeding, swagger/actuator/dev paths anonymous. `production`: helpers off, only health probes public. |
| `rgs.wallet.mode` | `internal` (default) / `operator` | `internal`: in-process seeded wallet, no external calls. `operator`: external WebClient → `RGS_WALLET_OPERATOR_URL`. |
| `rgs.simulator.cli-enabled` | `false` (default) / `true` | When `true`, runs one batch RTP simulation on startup. |

The `test` Spring profile remains for the integration-test harness only (Testcontainers,
deterministic JWT secret); it inherits demo + internal defaults.

## Build & Test

```bash
# Server
cd server
mvn -B verify          # compile + all tests (Testcontainers Postgres + Redis)
mvn -B package         # build runnable jar

# Client
cd client
pnpm install
pnpm verify            # lint + typecheck + unit tests + production build
pnpm test:coverage     # coverage report (≥ 80% threshold)
```

## Key URLs (demo mode)

| URL | Purpose |
|---|---|
| `http://localhost:5173` | Client dev server |
| `http://localhost:8080/swagger-ui.html` | Swagger UI (all endpoints) |
| `http://localhost:8080/v3/api-docs` | Raw OpenAPI spec |
| `http://localhost:8080/actuator/health` | Server health check |
| `http://localhost:8080/actuator/prometheus` | Prometheus metrics |
