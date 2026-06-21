# Running Locally

This guide walks you through spinning up the full Velocity RGS stack on your machine: Postgres, Redis, and the Spring Boot server. The server ships a built-in browser client (a self-contained HTML/CSS/JS app served directly by Spring Boot), so there is **no separate frontend build**.

---

## Prerequisites

| Tool | Minimum version | Install |
|---|---|---|
| **Docker** + Docker Compose | Docker Desktop 4.x | https://www.docker.com/products/docker-desktop |
| **JDK 21** | 21.0.x | https://adoptium.net or `sdk install java 21` |
| **Maven** | 3.9+ | Bundled with most IDEs, or `brew install maven` |

---

## Step 1 — Start Infrastructure

From the **repository root**:

```bash
docker compose up -d
```

This starts:

- **Postgres 16** on `localhost:5432` (db `rgs`, user `rgs`, password `rgs`)
- **Redis 7** on `localhost:6379`

Wait for both containers to be healthy:

```bash
docker compose ps
```

Both should show `healthy` in the STATUS column.

---

## Step 2 — Start the Server

From the **repository root**:

```bash
mvn spring-boot:run
```

The server starts on **`http://localhost:8080`** in **demo mode** (the default — no flags
needed). Flyway migrations run automatically on startup. To switch modes, edit the two
switches at the top of [`src/main/resources/application.yml`](src/main/resources/application.yml)
(`rgs.mode` and `rgs.wallet.mode`) or override at launch, e.g.
`mvn spring-boot:run -Drgs.mode=production -Drgs.wallet.mode=operator`.

Useful server URLs:

| URL | Purpose |
|---|---|
| `http://localhost:8080/` | Built-in browser client (demo mode) |
| `http://localhost:8080/swagger-ui.html` | Interactive API explorer |
| `http://localhost:8080/v3/api-docs` | Raw OpenAPI JSON spec |
| `http://localhost:8080/actuator/health` | Health check (`{"status":"UP"}`) |
| `http://localhost:8080/actuator/prometheus` | Prometheus metrics |

### Optional: customize server settings

The server uses sensible defaults for local development. Override via environment variables if needed:

```bash
export RGS_DB_URL=jdbc:postgresql://localhost:5432/rgs
export RGS_DB_USERNAME=rgs
export RGS_DB_PASSWORD=rgs
export RGS_REDIS_HOST=localhost
export RGS_REDIS_PORT=6379
# JWT secret — leave as default for local dev only
# export RGS_JWT_SECRET=my-local-dev-secret-min-32-chars!!
```

---

## Step 3 — Play the Demo

The built-in browser client is a self-contained vanilla **HTML/CSS/JS** app under
[`src/main/resources/static/`](src/main/resources/static/). It is served directly by Spring
Boot and needs **no Node.js, pnpm, or React build** — only the `demo` profile (Step 2).

1. With the server running, open **`http://localhost:8080/`** in your browser.
2. The page **auto-creates a demo player** (it mints a JWT with `PLAYER` + `ADMIN`
   roles via the public `POST /api/v1/dev/token` endpoint) — there is **no login**.
3. The 10,000 EUR demo balance is seeded automatically on first `init`.

What you can do from the page:

| Control | Backend call |
|---|---|
| **SPIN** (with **Bet** + **Power Bet** toggle) | `POST /api/v1/slot/spin` |
| **Buy Free Spins** / **Buy Pick & Collect** | `POST /api/v1/slot/feature/buy` |
| **Start Feature** (appears when a feature is pending) | `POST /api/v1/slot/feature/start` |
| **Pick tiles** (Pick & Collect board) | `POST /api/v1/slot/feature/pick` |
| **Run Simulation** (RTP simulator) | `POST /api/v1/admin/simulator/run` |
| **Set balance** (Admin panel) | `POST /api/v1/admin/wallet/balance` |
| **New Player** | re-mints a token + fresh session |

The **Last Response** panel shows the raw JSON of every call for quick debugging.

### Demo starting balance

The server seeds a **10,000 EUR** fake balance for every new player on first login. Use the
admin controls on the page to change it.

---

## Running Tests

From the **repository root**:

```bash
mvn -B verify
```

Integration tests spin up Testcontainers (Docker required). Full suite takes ~2–3 minutes.

```bash
mvn -B test            # unit + integration tests only
```

---

## Stopping Everything

```bash
# Stop the server — Ctrl+C in its terminal

# Stop and remove infra containers
docker compose down

# Optional: also remove the persistent Postgres volume
docker compose down -v
```

---

## Troubleshooting

### Port conflicts

| Port | Service | Override |
|---|---|---|
| `5432` | Postgres | `RGS_DB_URL=jdbc:postgresql://localhost:<port>/rgs` |
| `6379` | Redis | `RGS_REDIS_HOST=localhost RGS_REDIS_PORT=<port>` |
| `8080` | Server | Edit `server.port` in `src/main/resources/application.yml` |

### Server fails to start — "relation does not exist"

Flyway migrations did not run. Ensure Postgres is healthy before starting the server:

```bash
docker compose ps   # check Status = healthy
```

### "AUTH_FAILED" on every request

The token expired or the server is not in demo mode. Reload `http://localhost:8080/` to mint a
fresh token, and make sure the server is running with `rgs.mode=demo` so the
`/api/v1/dev/token` endpoint is available.

### Testcontainers integration tests fail on Windows

Ensure Docker Desktop is running and the Docker socket is accessible. Use the default Docker
Desktop settings (WSL 2 backend recommended).
