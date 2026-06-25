# Velocity RGS

An audit-grade iGaming slot platform: a deterministic Remote Gaming Server (RGS) built with
Java 21 + Spring Boot 3.x + Postgres + Redis. Every spin outcome, feature transition, and
balance change is decided server-side.

A self-contained **browser client** (vanilla HTML/CSS/JS) ships inside the server at
[`src/main/resources/static/`](src/main/resources/static/) and is served directly by Spring
Boot at `http://localhost:8080/` in demo mode — no separate frontend build, no Node/pnpm.

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

TODO

---

## Build & Test

```bash
docker compose up -d   # Postgres 16 + Redis 7
mvn -B verify          # compile + all tests (Testcontainers Postgres + Redis)
mvn -B package         # build runnable jar
mvn spring-boot:run    # run locally (demo mode) → http://localhost:8080/
```
