# Running Locally

This guide walks you through spinning up the full Velocity RGS stack on your machine: Postgres, Redis, the Spring Boot server, and the React/PixiJS client.

---

## Prerequisites

| Tool | Minimum version | Install |
|---|---|---|
| **Docker** + Docker Compose | Docker Desktop 4.x | https://www.docker.com/products/docker-desktop |
| **JDK 21** | 21.0.x | https://adoptium.net or `sdk install java 21` |
| **Maven** | 3.9+ | Bundled with most IDEs, or `brew install maven` |
| **Node.js** | 20.10+ | https://nodejs.org or `nvm install 20` |
| **pnpm** | 9.0+ | `npm install -g pnpm` |

> **Note:** JDK and Maven are needed for the server only. Node.js and pnpm are needed for the client only.

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

```bash
cd server
mvn spring-boot:run -Dspring-boot.run.profiles=demo
```

The server starts on **`http://localhost:8080`**. Flyway migrations run automatically on startup.

Useful server URLs:

| URL | Purpose |
|---|---|
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

## Step 3 — Start the Client

In a **new terminal**, from the repository root:

```bash
cd client

# First time only — copy the example env file
cp .env.example .env.local
# .env.local is pre-configured for local development (VITE_API_BASE_URL=http://localhost:8080)

# Install dependencies (first time only)
pnpm install

# Start the dev server
pnpm dev
```

The client starts on **`http://localhost:5173`**.

### `.env.local` defaults

The `.env.example` already points to the local server. The most important variables:

```env
VITE_API_BASE_URL=http://localhost:8080
VITE_DEFAULT_GAME_ID=aztec-fire
VITE_DEFAULT_CURRENCY=EUR
VITE_ENABLE_DEV_TOKEN=true   # show the login panel (needed for local demo)
```

Enable the debug HUD if you want to inspect trace IDs in the UI:

```env
VITE_ENABLE_DEBUG_HUD=true
```

---

## Step 4 — Play the Demo

1. Open **`http://localhost:5173`** in your browser.
2. You will be redirected to the login panel at `/auth`.
3. Fill in any `Player ID` and `Session ID` (e.g. `p-1001` / `s-2001`), set currency to `EUR`, and click **Get Token**.
   - This mints a JWT by calling the server's demo-only `POST /api/v1/dev/token` endpoint.
4. You are redirected to `/play` — the slot canvas loads and you can spin.

### Demo starting balance

The server seeds a **10,000 EUR** fake balance for every new player on first login. Use the admin panel to change it (see below).

---

## Using the Admin Panel

Navigate to `/admin` in the browser. You need a token with the `ADMIN` role:

1. On the `/auth` login panel, add `ADMIN` to the **Roles** field (alongside `PLAYER`).
2. Log in and navigate to `/admin`.

Admin tabs:
- **Wallet** — set an arbitrary balance for any player
- **Session** — inspect the live session state (Postgres + Redis cache)
- **Round** — view a round's matrix, RNG draws, and win lines
- **Replay** — reconstruct a round server-side and verify it is bit-exact
- **Simulator** — run the RTP simulator (up to 100k spins via HTTP)

---

## Running Tests

### Server tests

```bash
cd server
mvn -B verify
```

Integration tests spin up Testcontainers (Docker required). Full suite takes ~2–3 minutes.

### Client tests

```bash
cd client
pnpm test --run        # unit tests only (Vitest, jsdom)
pnpm test:coverage     # with coverage report
pnpm verify            # lint + typecheck + tests + production build
```

### Client E2E tests (Playwright)

The E2E suite requires the full stack to be running (Steps 1–3 above):

```bash
cd client
pnpm exec playwright test
```

---

## Stopping Everything

```bash
# Stop the client dev server — Ctrl+C in its terminal

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
| `8080` | Server | Edit `server.port` in `server/src/main/resources/application.yml` and update `VITE_API_BASE_URL` |
| `5173` | Client dev server | Edit `server.port` in `client/vite.config.ts` |

### Server fails to start — "relation does not exist"

Flyway migrations did not run. Ensure Postgres is healthy before starting the server:

```bash
docker compose ps   # check Status = healthy
```

### Client shows "Missing required env var VITE_API_BASE_URL"

You have not created `.env.local`. Run:

```bash
cp client/.env.example client/.env.local
```

### "AUTH_FAILED" on every request

The client has no token or the token expired. Go to `http://localhost:5173/auth` and log in again.
Make sure the server is running with `--profiles=demo` so the `/api/v1/dev/token` endpoint is available.

### Testcontainers integration tests fail on Windows

Ensure Docker Desktop is running and the Docker socket is accessible. Use the default Docker Desktop settings (WSL 2 backend recommended).
