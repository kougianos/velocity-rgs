# velocity-rgs (server)

Spring Boot 3.x / Java 21 deterministic Slot RGS. Architectural blueprint: [`be-requirements.md`](be-requirements.md). Milestone history: [`CHANGELOG.md`](CHANGELOG.md). Top-level quickstart: [`../README.md`](../README.md).

## Run

```bash
docker compose -f ../docker-compose.yml up -d
mvn spring-boot:run
```

The server boots in **demo mode** by default. All run modes live in a single
[`src/main/resources/application.yml`](src/main/resources/application.yml); flip
`rgs.mode` (`demo` | `production`) and `rgs.wallet.mode` (`internal` | `operator`)
there or override per-launch with `-Drgs.mode=…` / `-Drgs.wallet.mode=…`.

### Built-in demo test harness

A self-contained vanilla HTML/CSS/JS client lives in [`src/main/resources/static/`](src/main/resources/static/)
and is served at **`http://localhost:8080/`** (demo mode). It auto-mints a
`PLAYER`+`ADMIN` JWT (no login) and lets you spin, toggle power bet, buy/start
features, play Pick & Collect, run RTP simulations, and set balances directly
against this backend. No Node/pnpm build required.

## Test

```bash
mvn -B verify          # all tests (uses Testcontainers — Docker required)
mvn -B test            # unit + integration tests only
```

Integration tests boot a shared Postgres + Redis Testcontainer per JVM (see `TestcontainersInitializer`).

## QA helpers (demo mode only)

- `POST /api/v1/dev/token` — mint a JWT (no auth required)
- `POST /api/v1/admin/wallet/balance` — set arbitrary player balance (ADMIN role)
- `GET /api/v1/admin/session/{playerId}` — inspect persistent + cached session state (ADMIN role)
- `GET /api/v1/admin/round/{roundId}` — inspect persisted round including `rng_draws` (ADMIN role)
- `POST /api/v1/admin/simulator/run` — synchronous RTP simulation, persists an `audit_simulation_run` row (ADMIN role; available in `simulator` and `demo`-with-flag)

## Project layout

See [Appendix A.2](be-requirements.md) — the package skeleton is normative and must not be renamed.
