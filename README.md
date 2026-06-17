# Velocity RGS

Deterministic, audit-grade slot Remote Gaming Server (RGS) built with Java 21, Spring Boot 3.x, Postgres and Redis. See [`server/be-requirements.md`](server/be-requirements.md) for the full architectural blueprint and [`server/CHANGELOG.md`](server/CHANGELOG.md) for delivered milestones.

## Quickstart (local demo)

Prerequisites: JDK 21, Maven 3.9+, Docker (for Postgres + Redis).

```bash
# 1. Start infra
docker compose up -d

# 2. Boot the server in demo mode (fake money, internal wallet, dev token endpoint enabled)
cd server
mvn spring-boot:run -Dspring-boot.run.profiles=demo
```

Server listens on `http://localhost:8080`. Useful URLs:

- Swagger UI — `http://localhost:8080/swagger-ui.html`
- OpenAPI spec — `http://localhost:8080/v3/api-docs`
- Actuator — `http://localhost:8080/actuator/health`, `/actuator/prometheus`, `/actuator/info`

## Authenticate (demo profile)

Mint a JWT against the running server (no auth required, demo profile only):

```bash
curl -s -X POST http://localhost:8080/api/v1/dev/token \
  -H 'Content-Type: application/json' \
  -d '{"playerId":"p-1001","sessionId":"s-2001","currency":"EUR","roles":["PLAYER"],"ttlMinutes":60}'
```

For admin endpoints (replay, simulator, balance overrides) pass `"roles":["ADMIN"]`.

## Environment variables

| Variable | Default | Purpose |
|---|---|---|
| `RGS_DB_URL` | `jdbc:postgresql://localhost:5432/rgs` | Postgres JDBC URL |
| `RGS_DB_USERNAME` | `rgs` | Postgres username |
| `RGS_DB_PASSWORD` | `rgs` | Postgres password |
| `RGS_REDIS_HOST` | `localhost` | Redis host |
| `RGS_REDIS_PORT` | `6379` | Redis port |
| `RGS_JWT_SECRET` | dev-only placeholder (32 chars) | HS256 signing secret. **Must override in any non-demo deployment.** |
| `RGS_WALLET_OPERATOR_URL` | `http://localhost:9090` | External wallet base URL (`wallet-operator` profile only) |

## Profiles

| Profile | Wallet | Notes |
|---|---|---|
| `demo` (default) | Internal, seeded fake balance | Dev token + admin QA endpoints enabled; actuator anonymous |
| `wallet-internal` | Internal | Full audit logging |
| `wallet-operator` | External (WebClient → `RGS_WALLET_OPERATOR_URL`) | Actuator requires JWT |
| `simulator` | Internal | Boots the RTP simulator runner once; HTTP simulator endpoint exposed |
| `test` | Internal (Testcontainers) | CI / integration tests |

## Build, test, package

```bash
cd server
mvn -B verify        # compile + run tests against Testcontainers
mvn -B package       # build runnable jar at target/velocity-rgs-<version>.jar
mvn -B verify -Popenapi  # regenerate docs/openapi.yaml (boots app, requires Postgres + Redis)
```

## Repository layout

- `server/` — Spring Boot service (all backend code)
- `client/` — placeholder for the future thin renderer client
- `docker-compose.yml` — local Postgres + Redis infra
