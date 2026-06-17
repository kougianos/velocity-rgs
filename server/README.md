# velocity-rgs (server)

Spring Boot 3.x / Java 21 deterministic Slot RGS. Architectural blueprint: [`be-requirements.md`](be-requirements.md). Milestone history: [`CHANGELOG.md`](CHANGELOG.md). Top-level quickstart: [`../README.md`](../README.md).

## Run

```bash
docker compose -f ../docker-compose.yml up -d
mvn spring-boot:run -Dspring-boot.run.profiles=demo
```

## Test

```bash
mvn -B verify          # all tests (uses Testcontainers — Docker required)
mvn -B test            # unit + integration tests only
```

Integration tests boot a shared Postgres + Redis Testcontainer per JVM (see `TestcontainersInitializer`).

## QA helpers (demo profile only)

- `POST /api/v1/dev/token` — mint a JWT (no auth required)
- `POST /api/v1/admin/wallet/balance` — set arbitrary player balance (ADMIN role)
- `GET /api/v1/admin/session/{playerId}` — inspect persistent + cached session state (ADMIN role)
- `GET /api/v1/admin/round/{roundId}` — inspect persisted round including `rng_draws` (ADMIN role)
- `POST /api/v1/admin/simulator/run` — synchronous RTP simulation, persists an `audit_simulation_run` row (ADMIN role; available in `simulator` and `demo`-with-flag)

## Project layout

See [Appendix A.2](be-requirements.md) — the package skeleton is normative and must not be renamed.
