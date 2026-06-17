# AI Code Generation Blueprint: Velocity RGS Slot Client (React + PixiJS)

## Role & Context
You are an expert Frontend Engineer and Game Client Architect specializing in real-money iGaming clients that talk to a strict, deterministic Remote Gaming Server (RGS).

Your objective is to build the **Velocity RGS Slot Client**: a single-page web application that renders the `aztec-fire` 3×5 slot game and all its features (Free Spins, Power Bet, Bonus Buy, Pick & Collect) by talking exclusively to the backend defined in [server/be-requirements.md](../server/be-requirements.md). The client is a **thin presentation tier**: it never computes wins, never decides session transitions, never generates random numbers. Every spin, pick, and feature entry is decided server-side; the client merely renders the authoritative response and animates the player-visible artifacts.

Target stack: **React 18+**, **TypeScript (strict)**, **PixiJS v8** for the slot canvas, **Vite** for tooling.

---

## Architectural Principles & Strict Constraints

1. **Server is the Single Source of Truth.** The client must NEVER simulate spin outcomes, evaluate paylines, decide feature triggers, or mutate balance locally. The only fields the client may compute are visual derivatives (e.g. tween durations, particle counts). Every monetary or state-bearing value rendered on screen is read verbatim from the backend response.
2. **FSM Mirror, Not FSM Owner.** The client maintains a *read-only mirror* of the backend `GameState` (`BASE_GAME`, `FREE_SPINS_AWAITING`, `FREE_SPINS_LOOP`, `PICK_COLLECT_AWAITING`, `PICK_COLLECT_LOOP`). Available actions on screen MUST be driven exclusively by the `availableActions` array returned by the server. Any locally-cached state that disagrees with the server response on the next round MUST be discarded — server wins.
3. **Strict Session Versioning.** Every mutating request MUST carry the latest `sessionVersion` received from the server. After every success the client replaces the cached `sessionVersion` with the new one. On `SESSION_VERSION_CONFLICT` (409) the client MUST re-`init` to recover, never patch the version locally.
4. **Idempotency on Every Mutation.** Every call to `/api/v1/slot/spin`, `/feature/start`, `/feature/buy`, `/feature/pick` and every `/api/v1/wallet/{debit,credit,rollback}` MUST be sent with a freshly generated `Idempotency-Key` header (RFC 4122 v4 UUID). The same key MUST be retried verbatim on transport failure until a definitive HTTP response is received. The key is NEVER sent in the body.
5. **Correlation by Default.** Every outgoing HTTP request carries an `X-Trace-Id` header (UUID v4). The same trace id is mirrored into the browser console log line for that interaction and surfaced in the in-app error toast for support.
6. **Pixi Canvas is Stateless About Game Logic.** Pixi scenes consume *render commands* derived from server responses (`matrix`, `stopPositions`, `winLines`, `featuresTriggered`, `activeFeatureView`). They emit *intent events* (`userClickedSpin`, `userClickedPick(position)`) but never decide the outcome.
7. **No Secrets in the Bundle.** The frontend never embeds the JWT signing secret, wallet credentials, or any server-only config. JWTs are obtained at runtime via the demo-only `/api/v1/dev/token` endpoint (during M2 demo flow) or, in production-shaped builds, injected by the operator iframe shell.
8. **Resumability.** On page reload, the client MUST call `/api/v1/slot/init` first and reconstruct the entire UI from the response (including a live in-progress Pick & Collect feature surfaced via `activeFeatureView`). The client MUST NOT assume `BASE_GAME` on boot.
9. **Reveal Discipline.** The client must NEVER request, render, or speculate the contents of unrevealed Pick & Collect tiles. The only authoritative source for revealed tiles is `activeFeatureView.revealedPicks` plus the immediate `/feature/pick` response.
10. **HTML + CSS in Their Own Files.** React components own JSX only. CSS lives in colocated `.module.css` files (CSS Modules) — never inline `style={{...}}` blocks for non-dynamic styling, never `dangerouslySetInnerHTML` for HTML strings.
11. **Pixi and React Coexist, They Do Not Fight.** The Pixi `Application` mounts into a single `<canvas>` host managed by one React effect; React owns the HUD overlay (balance, buttons, modals, toasts), Pixi owns the reels, win animations, and the Pick & Collect board art.

---

## Technical Stack Requirements

* **Language:** TypeScript 5.x with `"strict": true`, `"noUncheckedIndexedAccess": true`, `"exactOptionalPropertyTypes": true`.
* **UI Framework:** React `^18.3.0` with function components and hooks only (no class components).
* **Build Tool:** Vite `^5.x` with the official `@vitejs/plugin-react` plugin.
* **Game Renderer:** PixiJS `^8.x` (canvas/WebGL2). Use `@pixi/react` only if it does not block upgrading Pixi; otherwise mount Pixi manually inside a React effect.
* **Routing:** `react-router-dom` `^6.x`.
* **Server State / Caching:** `@tanstack/react-query` `^5.x`. All wallet, init, and admin reads go through Query; mutations (`spin`, `feature/*`, `wallet/*`) use `useMutation` with retry disabled (idempotency is the client's responsibility).
* **Client State:** `zustand` `^4.x` for the session mirror store, the wallet store, and the UI store. Redux is not introduced.
* **HTTP Client:** `axios` `^1.x` with a single configured instance exposing interceptors for: `Authorization`, `Idempotency-Key`, `X-Trace-Id`, error → typed `ApiError` mapping.
* **Forms / Validation:** `react-hook-form` + `zod` for any QA admin form (set-balance, dev-token).
* **Sound:** `howler` `^2.x` for SFX (spin loop, win sting, pick reveal).
* **Animations (HUD):** `framer-motion` for HUD overlays. Pixi handles in-canvas animation via its own ticker + GSAP or built-in tweens; do NOT introduce GSAP unless Pixi-native tweens prove insufficient.
* **Lint / Format:** ESLint (`@typescript-eslint`, `eslint-plugin-react`, `eslint-plugin-react-hooks`, `eslint-plugin-jsx-a11y`) + Prettier. Husky + lint-staged on `pre-commit`.
* **Testing:**
  * Unit / component: **Vitest** + **@testing-library/react** + **jsdom**.
  * Visual regression of the HUD: **Storybook** + **@storybook/test-runner** (M9, optional).
  * E2E: **Playwright** (`@playwright/test`) driving a real browser against a `demo`-profile backend (Testcontainers-equivalent setup in CI).
  * Network mocking in dev: **MSW** (`msw` v2) so feature authors can iterate without a running backend.
* **API Type Generation:** `openapi-typescript` consumes `server/docs/openapi.yaml` and emits `src/api/generated/openapi.ts`. Generated types are the canonical source of HTTP contract shapes; hand-written DTOs MUST extend or alias the generated types and never redeclare field names.
* **Package Manager:** `pnpm` (deterministic lockfile, fast install). Node `>= 20.10`.

---

## Frontend Project Rules

1. **Folder Layout (Authoritative)** — see [Appendix B.1](#b1-folder-layout). Every new module MUST land under its assigned package. No file under `src/api/**` may import from `src/game/**` or `src/pages/**` (one-way dependency rule enforced by `eslint-plugin-import` `no-restricted-paths`).
2. **One Module = One Responsibility.** A `*.tsx` file owns JSX + behavior wiring. A colocated `*.module.css` owns presentation. A colocated `*.test.tsx` owns unit tests. A colocated `*.stories.tsx` (when present) owns Storybook docs.
3. **No Cross-Feature Imports.** `feature/freespins` may not import from `feature/pickcollect` and vice-versa. Shared primitives live in `game/ui/common`.
4. **API Contracts are Generated, Not Invented.** When the backend ships a new endpoint:
   1. Regenerate `openapi.yaml` on the server (`mvn -B verify`).
   2. Copy the file to `client/openapi/openapi.yaml`.
   3. Run `pnpm api:gen` to refresh `src/api/generated/openapi.ts`.
   4. Then write the typed wrapper in `src/api/<domain>/`.
   Hand-typing a DTO that contradicts `openapi.yaml` is a CI failure.
5. **Enums Mirror Backend One-to-One.** TypeScript string-literal unions or `const`-asserted objects mirror exactly: `GameState`, `GameCommand`, `WalletTransactionType`, `RollbackReason`, `BonusBuyType`, `PickTileType`, `ErrorCode`, `WalletTransactionStatus`. Inventing a new value (e.g. `"AUTO_SPIN"`) is forbidden until the server adds it.
6. **Money Formatting.** All `BigDecimal` amounts arrive as JSON numbers or strings; the client wraps them via a `Money` helper backed by `decimal.js-light` (never `Number`), formats per `Intl.NumberFormat` with the player's currency, and rounds for display only.
7. **Logging Discipline.** A single `logger` module emits structured `console.info`/`warn`/`error` lines tagged with `traceId`, `playerId`, `sessionId`, `roundId` when available. Logs sent to a remote sink (M9, optional) use the same shape.
8. **No `any`, No `as` Casts on API Boundaries.** API response parsers use `zod` runtime validation against the generated types. If a payload fails validation the client logs at `ERROR`, shows a generic "Game communication error" toast, and refuses to mutate state.
9. **Accessibility Baseline.** Every interactive HUD control has an `aria-label`, supports keyboard focus, and visible focus ring. The Pixi canvas exposes an `aria-hidden="true"` host plus a textual live-region (`aria-live="polite"`) that announces spin results for screen-readers ("Win: 1.50 EUR on line 3").
10. **Performance Budget.** Initial JS bundle ≤ 350 KB gzipped excluding Pixi (Pixi loaded async). First spin interaction available within 2.5 s on a 4× CPU-throttled mid-range mobile profile.

---

## Design Rules

1. **HUD vs. Canvas Separation.** Player-controlled buttons (Spin, Bet+/−, Power Bet toggle, Bonus Buy, Pick) live in the React HUD layer rendered on top of the Pixi canvas. Pixi never draws DOM-style buttons.
2. **Disable, Don't Hide.** Actions not present in `availableActions` are rendered disabled with a tooltip explaining the gate ("Spin disabled — finish Free Spins"). Hidden controls confuse returning players.
3. **Idle-State Visual Cues.** Every awaiting state (`FREE_SPINS_AWAITING`, `PICK_COLLECT_AWAITING`) ships a distinct overlay with a single, obvious "Start" CTA bound to `/feature/start`.
4. **Bonus Buy Gating.** The "Buy Feature" panel renders only when `featureFlags.bonusBuyEnabled === true` in the `/init` response. Each buy option shows: `buyType`, `costMultiplier × betSize = totalCost`, "Not enough balance" disablement, and a confirmation dialog with the resolved cost in the player's currency.
5. **Pick & Collect Board.** Drawn in Pixi. Unopened tiles use a single uniform "hidden" art. Opened tiles flip to their resolved `PickTileType` + `resolvedValue`. The board is never re-shuffled client-side; positions are stable across picks.
6. **Reason Code Translation.** `reasonCodes` (e.g. `TRIGGERED_BY_SCATTER`, `ENTERED_VIA_BUY`, `MAX_WIN_CAPPED`, `PICK_COMPLETED`, `RETRIGGERED_FREE_SPINS`) map through `src/i18n/reasonCodes.ts` to human-readable banners. Unknown reason codes show the raw code in dev profile only.
7. **Error UX.** Domain errors (`INSUFFICIENT_FUNDS`, `BONUS_BUY_DISABLED`, `MAX_WIN_REACHED`, `SESSION_VERSION_CONFLICT`, `ILLEGAL_STATE_TRANSITION`) each have a dedicated toast/modal with a recovery action. Generic `INTERNAL_ERROR` shows a "Game communication error" modal with the `traceId` and a "Retry" button (re-`init`).
8. **Latency Feedback.** Any mutation taking > 250 ms shows a non-blocking spinner overlay on the relevant button; > 1.5 s adds a "Still working…" sub-caption. The Spin button is disabled from click until response.
9. **Resume Banner.** When `/init` returns a non-`BASE_GAME` state, the client surfaces a "Resuming your previous round…" banner that auto-dismisses on the first successful action.
10. **No Auto-Spin in Scope.** The spec does not define an auto-spin contract; the client MUST NOT introduce one client-side. Reserved for a future server-side feature.
11. **Sound Defaults Muted.** First-load audio is muted (browser autoplay policies). A persistent mute toggle in the HUD respects user preference via `localStorage`.

---

## Implementation Roadmap & Milestone Breakdowns

Execute this implementation sequentially. Each milestone is self-contained, ends with a green `pnpm verify` (lint + typecheck + unit tests + production build), and produces a stable foundation that the next milestone builds upon. **Do not move forward** until the current milestone's logic and its corresponding tests are complete and stable.

Milestone dependency graph (strict):

```
M0 (Bootstrap & Tooling)
   └── M1 (API Layer & Generated Types)
         └── M2 (Auth, Session Init, FSM Mirror)
               └── M3 (Wallet Panel & Balance Feed)
                     └── M4 (Pixi Stage & Reel Rendering Core)
                           └── M5 (Base Game Spin Loop — End-to-End Playable)
                                 └── M6 (Free Spins UI & Power Bet)
                                       └── M7 (Bonus Buy & Pick & Collect)
                                             └── M8 (QA / Admin Tooling)
                                                   └── M9 (Observability, A11y, Perf Hardening)
```

### Milestone 0: Project Bootstrap & Tooling
Establish the skeleton every later milestone depends on. No game logic in this milestone.

* **Task 0.1:** Initialize `client/` with `pnpm create vite@latest velocity-rgs-client -- --template react-ts`. Move generated files under `client/` so the repo layout becomes `client/{src,public,index.html,vite.config.ts,package.json,...}`.
* **Task 0.2:** Pin runtime deps: `react`, `react-dom`, `react-router-dom`, `pixi.js@^8`, `@tanstack/react-query`, `zustand`, `axios`, `decimal.js-light`, `zod`, `howler`, `framer-motion`. Pin dev deps: `typescript@^5`, `vite`, `@vitejs/plugin-react`, `vitest`, `@testing-library/react`, `@testing-library/jest-dom`, `jsdom`, `@playwright/test`, `msw@^2`, `openapi-typescript`, ESLint stack, Prettier, Husky, lint-staged.
* **Task 0.3:** Configure `tsconfig.json` with `strict`, `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`, `noImplicitOverride`, `verbatimModuleSyntax`, path alias `@/*` → `src/*`.
* **Task 0.4:** Configure `vite.config.ts`: React plugin, `@/*` alias, `define` for build-time env, code-splitting hint for Pixi (`build.rollupOptions.output.manualChunks.pixi`).
* **Task 0.5:** Configure ESLint with `eslint-plugin-import/no-restricted-paths` enforcing the folder-layout boundaries from Rule #1.
* **Task 0.6:** Configure Vitest (`vitest.config.ts`) with `environment: 'jsdom'`, setup file installing `@testing-library/jest-dom`. Configure Playwright (`playwright.config.ts`) with `baseURL` from `RGS_BASE_URL`.
* **Task 0.7:** Add `.env.example` documenting: `VITE_API_BASE_URL`, `VITE_DEFAULT_GAME_ID`, `VITE_DEFAULT_CURRENCY`, `VITE_ENABLE_MSW`. Add a small `env.ts` that parses these with `zod` at startup and fails fast on missing values.
* **Task 0.8:** Wire `pnpm verify` (alias for `pnpm lint && pnpm typecheck && pnpm test --run && pnpm build`). Add Husky `pre-commit` running `lint-staged`.
* **Task 0.9:** Create `index.html` with a single `<div id="root">` and a `<canvas id="pixi-host">` placeholder. Create `src/main.tsx` mounting `<App/>` inside `QueryClientProvider` + `BrowserRouter`. Render a stub `<LobbyPage/>` reading "Velocity RGS — booting…".
* **Task 0.10:** Set up MSW worker (`src/mocks/browser.ts`) gated by `VITE_ENABLE_MSW=true` so future milestones can iterate offline.

Tests for M0:
* `pnpm verify` is green.
* `App` renders the boot string and is queryable by role.
* `env.ts` throws on missing required env keys (Vitest spec).

Foundation guarantee for M1+: a typed, lint-clean React/Vite shell with test runner, MSW, and bundle config ready.

### Milestone 1: API Layer & Generated Types
Build the typed HTTP boundary that mirrors the server contracts. No UI changes beyond a debug page.

* **Task 1.1:** Copy `server/docs/openapi.yaml` into `client/openapi/openapi.yaml`. Add `pnpm api:gen` script: `openapi-typescript ./openapi/openapi.yaml -o ./src/api/generated/openapi.ts`. Commit the generated file. CI must fail when the freshly generated file differs from the committed snapshot.
* **Task 1.2:** Build `src/api/http/axios.ts`: a singleton `axios` instance with `baseURL = env.VITE_API_BASE_URL`. Install request interceptors that attach `Authorization: Bearer <jwt>` (from `authStore`), `X-Trace-Id` (UUID v4 per request), and, for mutating endpoints, `Idempotency-Key` (UUID v4 supplied by caller, never auto-generated by the interceptor — callers own the key for retry semantics).
* **Task 1.3:** Build `src/api/http/errors.ts`: response interceptor that on non-2xx parses the body as `ApiError` (via `zod`) and rethrows a typed `RgsHttpError` (fields: `code: ErrorCode`, `message`, `httpStatus`, `traceId`, `timestamp`, `details?`). Transport failures (no response) rethrow `RgsNetworkError`.
* **Task 1.4:** Build typed wrappers under `src/api/slot/`: `init.ts`, `spin.ts`, `featureStart.ts`, `featureBuy.ts`, `featurePick.ts`. Each exports an async function whose parameters mirror the request DTO and whose return type mirrors the response DTO (sourced from `src/api/generated/openapi.ts`).
* **Task 1.5:** Build typed wrappers under `src/api/wallet/`: `authenticate.ts`, `balance.ts`, `debit.ts`, `credit.ts`, `rollback.ts`. `debit`, `credit`, `rollback` accept an `idempotencyKey: string` parameter (required).
* **Task 1.6:** Build typed wrappers under `src/api/admin/`: `replay.ts`, `setBalance.ts`, `getSession.ts`, `getRound.ts`, `simulatorRun.ts` (each gated to admin routes per server profile).
* **Task 1.7:** Build typed wrapper for `src/api/dev/token.ts` (`POST /api/v1/dev/token` — demo profile only).
* **Task 1.8:** Re-export the canonical enums verbatim under `src/api/enums.ts` as TypeScript string-literal types: `GameState`, `GameCommand`, `WalletTransactionType`, `RollbackReason`, `BonusBuyType`, `PickTileType`, `WalletTransactionStatus`, `ErrorCode`. Add unit assertions that every value matches the generated OpenAPI schema (compile-time `satisfies`).
* **Task 1.9:** Build `src/common/money/Money.ts`: wraps `decimal.js-light`, exposes `add`, `subtract`, `multiply(integerOrDecimal)`, `compareTo`, `format(currency, locale)`, `toPlain()`. Currency validation restricted to `EUR`, `USD`.
* **Task 1.10:** Build `src/common/idempotency/key.ts`: `newIdempotencyKey(): string` returning a UUID v4, plus a small `IdempotentMutation<TReq,TRes>` helper that retains the key across retries (used by all mutating callers).
* **Task 1.11:** Add a temporary `/debug` route that fires `init` against a running demo backend and pretty-prints the response. Used as a smoke test only; removed in M9.

Tests for M1:
* Vitest specs for `RgsHttpError` parsing across each `ErrorCode` (mapping table fully covered with fixtures).
* `Money` arithmetic and rounding correctness (parity with backend `HALF_UP` scale 2).
* MSW handlers for `/api/v1/slot/init` returning the canonical A.7 sample; integration test asserts the wrapper returns the typed response with no `any`.
* `pnpm api:gen` produces a byte-identical file to the committed one (CI guard).

Foundation guarantee for M2+: every later layer talks to the backend exclusively through these typed wrappers and the surface is `any`-free.

### Milestone 2: Auth, Session Init & FSM Mirror
Bring the player into a usable session. Still no game art; a minimal placeholder UI confirms wiring.

* **Task 2.1:** Build `src/auth/authStore.ts` (Zustand): `{ token: string | null, playerId: string | null, sessionId: string | null, currency: 'EUR'|'USD' | null, roles: string[] }` with `setToken`, `clear`, and a derived `isAuthenticated` selector. JWT is stored in memory only (NOT `localStorage`) to mirror operator-iframe expectations; a `sessionStorage` fallback is acceptable for the demo profile and is gated by an env flag.
* **Task 2.2:** Build the demo dev-token panel at `/auth` route (visible only when `VITE_ENABLE_DEV_TOKEN=true`): a form (playerId, sessionId, currency, roles, ttlMinutes) backed by `react-hook-form` + `zod`. Submitting calls `POST /api/v1/dev/token`, stores the token in `authStore`, and navigates to `/play`.
* **Task 2.3:** Build `src/session/sessionStore.ts` (Zustand): the read-only FSM mirror — `{ sessionId, sessionVersion, gameId, mathVersion, currentState: GameState, remainingFreeSpins, accumulatedFreeSpinsWin, currentBet, availableActions: GameCommand[], featureFlags, activeFeatureView }`. Exposes `applyInitResponse(resp)`, `applySpinResponse(resp)`, `applyFeatureStartResponse(resp)`, `applyFeatureBuyResponse(resp)`, `applyPickResponse(resp)`. Each setter replaces (not merges) the relevant fields and bumps `sessionVersion` from the response.
* **Task 2.4:** Build `src/session/useSessionInit.ts`: a React Query `useQuery({ queryKey: ['init', gameId, currency], queryFn: () => slotApi.init({...}) })` that runs once the player is authenticated and pipes the response into `sessionStore.applyInitResponse`. On `SESSION_NOT_FOUND` the hook re-fires `init`.
* **Task 2.5:** Build `src/session/useSessionRecovery.ts`: a global error boundary listener that on `SESSION_VERSION_CONFLICT` clears the session store and re-runs `useSessionInit`. Toast: "Session refreshed — try again."
* **Task 2.6:** Build a placeholder `src/pages/PlayPage.tsx` that renders the current `GameState`, `balance`, `currentBet`, `remainingFreeSpins`, and a JSON pretty-print of `availableActions`. No real game art yet.
* **Task 2.7:** Wire React Router routes: `/auth` (dev-token), `/play` (PlayPage, requires `isAuthenticated`), `/admin` (placeholder, M8), `/` (redirect to `/auth` or `/play` based on auth).

Tests for M2:
* `authStore` round-trip (set/clear).
* `sessionStore.applyInitResponse` correctly mirrors every field of the canonical A.7 init sample.
* `useSessionInit`: MSW stubs `/init` → store is populated, `PlayPage` renders the expected `currentState`.
* On `SESSION_VERSION_CONFLICT` toast appears and a re-init request fires (assert MSW request count).
* Playwright smoke: open `/`, fill dev-token form, land on `/play`, see the boot session readout.

Foundation guarantee for M3+: every visual layer can subscribe to `sessionStore` and trust that it reflects the latest server snapshot.

### Milestone 3: Wallet Panel & Balance Feed
Show the player's money, react to debits/credits without optimistic mutation.

* **Task 3.1:** Build `src/wallet/walletStore.ts` (Zustand): `{ balance: Money | null, currency: string | null, lastUpdatedAt: Date | null }` with `applyBalance(resp)` and `applyTransactionEffect(resp)` (the wallet response from a debit/credit carries `balanceAfter`; we trust the server).
* **Task 3.2:** Build `useWalletBalance` (`react-query`): periodically refetches `/api/v1/wallet/balance` (default 30 s, paused while a mutation is in flight) and feeds `walletStore.applyBalance`. Also fetched once after every successful spin/feature settlement to reconcile.
* **Task 3.3:** Build `src/wallet/components/BalancePanel.tsx` + `BalancePanel.module.css`: the persistent HUD pill showing `Money.format(balance, currency)`. Pulse animation on balance change (framer-motion).
* **Task 3.4:** Build `src/wallet/components/BetSelector.tsx` + module CSS: a stepper (`-` / current bet / `+`) that selects from a fixed bet ladder (`[0.20, 0.50, 1.00, 2.00, 5.00, 10.00]` for demo; configurable). Disabled when `currentState !== 'BASE_GAME'`.
* **Task 3.5:** Wire the `BalancePanel` and `BetSelector` into `PlayPage`.
* **Task 3.6:** Add a `src/wallet/errors.ts` mapping that surfaces friendly toasts for `INSUFFICIENT_FUNDS`, `CURRENCY_MISMATCH`, `DUPLICATE_TRANSACTION` (the latter should never reach the user; logged at ERROR).

Tests for M3:
* `walletStore.applyTransactionEffect` updates balance to `balanceAfter` exactly.
* MSW spec: a successful spin response carrying a `betDebited` value triggers a follow-up balance refetch.
* Component test: `BetSelector` is disabled when `currentState === 'FREE_SPINS_LOOP'`.
* Playwright: balance pill renders correct demo balance after `/init`.

Foundation guarantee for M4+: the HUD reliably reflects server-authoritative balance.

### Milestone 4: Pixi Stage & Reel Rendering Core
Stand up the Pixi scene. No spin animation yet; renders a static 3×5 grid from a server matrix.

* **Task 4.1:** Build `src/game/pixi/PixiApp.ts`: thin wrapper around `new PIXI.Application({ ... })`. Exposes `mount(canvas: HTMLCanvasElement)`, `destroy()`, `stage: PIXI.Container`, `ticker`.
* **Task 4.2:** Build `src/game/pixi/usePixiApp.ts`: a React hook that mounts a `PixiApp` into the `<canvas id="pixi-host"/>` element on `useEffect` and destroys it on unmount. Hot-reload safe (idempotent mount).
* **Task 4.3:** Asset pipeline: place symbol sprites under `public/assets/symbols/<symbolId>.png` (placeholder art OK). Build `src/game/pixi/assets.ts` that uses `PIXI.Assets.load(...)` and returns a `Map<number, PIXI.Texture>` keyed by `symbolId`.
* **Task 4.4:** Build `src/game/pixi/Reel.ts`: a `PIXI.Container` representing one reel column. Method `setSymbols(symbolIds: number[])` redraws the 3 visible symbols using preloaded textures.
* **Task 4.5:** Build `src/game/pixi/SlotGrid.ts`: composes 5 `Reel` instances side-by-side. Method `renderMatrix(matrix: number[][])` calls each reel's `setSymbols`.
* **Task 4.6:** Build `src/game/pixi/SlotStage.tsx`: a React component that owns `usePixiApp`, instantiates `SlotGrid`, and subscribes to a `lastSpin` selector on `sessionStore`. For now, on mount it renders a placeholder static matrix derived from the math config (or a known fixture).
* **Task 4.7:** Pull symbol metadata for `aztec-fire` from a small embedded `src/game/math/aztec-fire.ts` mirror file (NOT the full math config — just `{ symbolId -> name }`) so we can display symbol names in debug overlays.
* **Task 4.8:** Wire `SlotStage` into `PlayPage` above the HUD layer (CSS grid: canvas in the back, HUD in the front).

Tests for M4:
* Vitest: `SlotGrid.renderMatrix` produces 15 sprite children with the expected textures (mock `PIXI.Texture`).
* Playwright visual: load `/play`, assert the canvas mounts (`canvas` element width > 0).

Foundation guarantee for M5+: a server-supplied matrix can be rendered to screen deterministically.

### Milestone 5: Base Game Spin Loop — End-to-End Playable
First milestone that produces a playable demo: a player can click Spin, see debit, watch reels animate to the server-supplied stop positions, and see the win render.

* **Task 5.1:** Build `src/game/spin/useSpin.ts`: a `useMutation` that calls `slotApi.spin({ gameId, sessionId, sessionVersion, betSize, powerBetActive })` with a fresh `Idempotency-Key` per click. Retry is disabled. On network failure the same key is reused for the next user-triggered retry.
* **Task 5.2:** Build the Spin button (`src/game/ui/SpinButton.tsx` + module CSS). Enabled iff `availableActions.includes('SPIN')` and not currently mutating. On click: invokes `useSpin.mutate()` and shows a spinner state.
* **Task 5.3:** Build `src/game/pixi/SpinAnimator.ts`: takes a `SpinResponse` (`matrix`, `stopPositions`, `winLines`) and drives the reels through: (a) instant blur start, (b) per-reel timed deceleration (staggered by 80 ms), (c) snap-to `matrix`, (d) win-line highlight pass (Pixi `Graphics` overlays following each `WinLine.lineId` payline coordinate set from the math config), (e) totalWin counter tween in the HUD.
* **Task 5.4:** On a successful spin response: `sessionStore.applySpinResponse(resp)` → `SpinAnimator.play(resp)` → after animation completes, refetch wallet balance (Task 3.2 already covers this) and re-enable controls.
* **Task 5.5:** Wire the saga semantics on the client side: nothing to do — the server's debit/credit is internal. The client only renders `betDebited` and `totalWin`. If the server returns a 4xx domain error before any animation starts, surface the toast (Task 3.6 / Design Rule #7) and leave the reels untouched.
* **Task 5.6:** Reason code banners (`MAX_WIN_CAPPED`, `TRIGGERED_BY_SCATTER`, etc.) appear as transient overlays driven by `featuresTriggered.reasonCodes`.
* **Task 5.7:** Honor Latency Feedback (Design Rule #8): spin button shows spinner > 250 ms, "Still working…" > 1500 ms.

Tests for M5:
* Vitest: a stubbed `useSpin.mutate()` call increments `sessionVersion` in `sessionStore` after success.
* Component test: the Spin button is disabled while in `FREE_SPINS_AWAITING`.
* MSW spec: simulated `INSUFFICIENT_FUNDS` → toast appears, reels NOT animated, store unchanged.
* Playwright E2E against `demo` backend: dev-token login → `/play` → click Spin 5 times → assert the HUD reflects the cumulative balance and the canvas shows the latest matrix.

Foundation guarantee for M6+: the spin loop is the canonical mutation lifecycle; every later command (`feature/start`, `feature/buy`, `feature/pick`) reuses the same `Idempotency-Key` + `sessionVersion` discipline.

### Milestone 6: Free Spins UI & Power Bet
Render and play the Free Spins feature lifecycle plus the Power Bet toggle.

* **Task 6.1:** Build `src/game/feature/freespins/FreeSpinsOverlay.tsx` + module CSS: renders when `currentState` is `FREE_SPINS_AWAITING` (CTA "Start Free Spins" → `POST /feature/start { featureType: 'FREE_SPINS' }`) or `FREE_SPINS_LOOP` (badge "Free Spins: N remaining · Accumulated: X").
* **Task 6.2:** Build `useStartFeature` mutation hook (`featureType: 'FREE_SPINS' | 'PICK_COLLECT'`). Disables when not in the matching awaiting state.
* **Task 6.3:** Reuse `useSpin` inside `FREE_SPINS_LOOP`; the client must NOT send `betSize` modifications (server enforces `betLockedToTriggerBet`). The `BetSelector` is disabled and shows the locked bet.
* **Task 6.4:** Animate retriggers: when `featuresTriggered.reasonCodes.includes('RETRIGGERED_FREE_SPINS')`, show a "+N Free Spins" burst overlay.
* **Task 6.5:** On feature settlement (response carrying `currentState === 'BASE_GAME'` after a free spin), animate the accumulated win credit into the balance via a counter tween.
* **Task 6.6:** Build the Power Bet toggle (`src/game/ui/PowerBetToggle.tsx`): controlled component bound to a local `usePowerBet` Zustand slice. Visible only when `featureFlags.powerBetEnabled === true`. Disabled when `currentState !== 'BASE_GAME'`. The toggle's value is passed verbatim to `useSpin` as `powerBetActive`.
* **Task 6.7:** Display "Power Bet active — bet multiplier 1.5×" caption next to the Spin button when toggled. The actual debit amount comes from the server response (`betDebited`); the client never recomputes it.

Tests for M6:
* `FreeSpinsOverlay` renders the correct CTA in `FREE_SPINS_AWAITING` and the correct badge in `FREE_SPINS_LOOP` (component tests with mocked store).
* MSW spec: starting Free Spins transitions the store and disables the Bonus Buy button.
* Playwright E2E: simulate a scatter trigger (via MSW or against a deterministic backend seed) → start free spins → run 10 spins → assert final balance increment.
* Power Bet toggle is hidden when `featureFlags.powerBetEnabled` is false.

Foundation guarantee for M7+: feature-state UI patterns are reusable for Pick & Collect.

### Milestone 7: Bonus Buy & Pick & Collect
Ship the two remaining feature paths.

* **Task 7.1:** Build `src/game/feature/bonusbuy/BonusBuyPanel.tsx` + module CSS: lists the math config's `bonusBuyOptions` (loaded via a small `useGameMathSummary` query against a future `/api/v1/game/math-summary` endpoint OR a static client-side mirror if such endpoint does not yet exist — flag this in `client/openapi/gap-report.md`). For each option shows `buyType`, computed `cost = betSize × costMultiplier` (display only; the server is authoritative), and a "Buy" button.
* **Task 7.2:** "Buy" → confirmation modal → `useBuyFeature` mutation (`POST /api/v1/slot/feature/buy`, fresh `Idempotency-Key`). On success: `sessionStore.applyFeatureBuyResponse(resp)` → transition into the returned `enteredState`; the existing `FreeSpinsOverlay` / Pick & Collect board reacts accordingly.
* **Task 7.3:** Build `src/game/feature/pickcollect/PickBoard.ts` (Pixi container): renders `boardSize` tiles in a responsive grid. Tiles are interactive (`eventMode = 'static'`, `cursor = 'pointer'`). Click emits `onPick(position)`.
* **Task 7.4:** Build `src/game/feature/pickcollect/PickBoardScene.tsx`: React component that owns the Pixi `PickBoard`, subscribes to `sessionStore.activeFeatureView` (a `PickCollectFeatureView`), and re-renders opened positions / revealed picks whenever the view changes.
* **Task 7.5:** Build `useFeaturePick` mutation hook calling `POST /api/v1/slot/feature/pick { position }` with a fresh `Idempotency-Key`. On success: `sessionStore.applyPickResponse(resp)` → board animates the reveal of the picked tile using `resp.resolvedTileType` and `resp.resolvedValue` → counters update from `resp.currentCollected` / `resp.remainingPicks`.
* **Task 7.6:** On `featureCompleted === true`: play settlement animation, then on `currentState === 'BASE_GAME'` trigger a balance reconciliation (already wired in Task 3.2). Surface `PICK_COMPLETED` reason code banner.
* **Task 7.7:** Build `src/game/feature/pickcollect/PickCollectOverlay.tsx`: covers `PICK_COLLECT_AWAITING` (CTA "Start Pick & Collect" → `POST /feature/start { featureType: 'PICK_COLLECT' }`) and the `PICK_COLLECT_LOOP` HUD (current collected, remaining picks). Mirrors `FreeSpinsOverlay` structure.
* **Task 7.8:** Hard guards: the client MUST never POST `position` for a tile already in `openedPositions`. The board disables already-opened tiles before the click reaches the network. The server is still authoritative; a 409 from the server triggers a re-init.

Tests for M7:
* Component test: `BonusBuyPanel` hides when `featureFlags.bonusBuyEnabled === false`.
* Component test: `PickBoard` disables tiles in `openedPositions`.
* MSW E2E: full Pick & Collect journey from `PICK_COLLECT_AWAITING` → start → 5 picks → `featureCompleted` → return to `BASE_GAME`.
* Playwright against `demo` backend: bonus buy a Pick & Collect feature, complete the board, assert balance increased by the response's `featureTotalWin`.

Foundation guarantee for M8: every game lifecycle path is reachable from the UI.

### Milestone 8: QA / Admin Tooling
Mirror the backend's QA helpers so testers can exercise edge cases without curl.

* **Task 8.1:** Build `/admin` route (gated by `roles.includes('ADMIN')` on the JWT). Tabbed layout: "Wallet", "Session", "Round", "Replay", "Simulator".
* **Task 8.2:** Wallet tab → form bound to `POST /api/v1/admin/wallet/balance` (`playerId`, `currency`, `balance`). Shows the resulting `SetBalanceResponse` and updates `walletStore` if the edited player matches the current session.
* **Task 8.3:** Session tab → input `playerId` → `GET /api/v1/admin/session/{playerId}` → renders the JSON inspection with a "Resume as this player" button (dev profile only).
* **Task 8.4:** Round tab → input `roundId` → `GET /api/v1/admin/round/{roundId}` → renders the matrix + RNG draws + win lines using the same `SlotGrid` renderer.
* **Task 8.5:** Replay tab → input `roundId` → `POST /api/v1/admin/replay/{roundId}` → renders the reconstructed matrix side-by-side with the stored one and a green "match" badge if equal.
* **Task 8.6:** Simulator tab → form for `RtpSimulationRequest` (`gameId`, `mathVersion`, `bet`, `spinsBaseGame`, `spinsBonusBuyFreeSpins`, `spinsBonusBuyPickCollect`, `pickStrategy` ∈ `SEQUENTIAL`/`RANDOM_UNOPENED`/`COLLECT_FIRST`) → submits to `POST /api/v1/admin/simulator/run` → renders the `RtpReport` (three RTP channels, hit frequency, max win multiplier, payout distribution histogram with `recharts` or pure SVG, latency p50/p95/p99).
* **Task 8.7:** Ship `client/http/velocity-rgs-client.http` (VS Code REST Client format) mirroring the server's `server/http/velocity-rgs.http` blocks for parity (useful when QA tests via REST client too).

Tests for M8:
* Component tests for each admin form: validation errors surface for invalid inputs.
* MSW spec: replay tab shows the "match" badge when reconstructed matrix equals stored.
* Playwright: admin user opens simulator, runs a 1000-spin job, sees the report rendered.
* Non-admin user is redirected away from `/admin`.

Foundation guarantee for M9: every backend tool has a UI affordance.

### Milestone 9: Observability, A11y, Performance Hardening
Operational polish. No new features.

* **Task 9.1:** Implement `src/observability/logger.ts` and route all `console.*` calls through it. Structured log lines: `{ level, traceId, playerId, sessionId, roundId, gameId, message, ...rest }`. Optionally pipe to a remote sink behind `VITE_LOG_SINK_URL`.
* **Task 9.2:** Mirror `X-Trace-Id` in error toasts and in the in-app debug HUD (collapsible).
* **Task 9.3:** Full a11y audit: every interactive control has `aria-label`, focus order is keyboard-traversable, color contrast ≥ 4.5:1. Pixi canvas has the textual live-region announcing spin results.
* **Task 9.4:** Performance pass: code-split Pixi into a separate chunk, lazy-load admin routes, preload symbol atlases, audit bundle with `vite-bundle-visualizer`. Enforce the budget from Frontend Rule #10 in CI via `bundlewatch` or equivalent.
* **Task 9.5:** Add `web-vitals` integration that logs LCP / INP / CLS to the logger.
* **Task 9.6:** Remove the debug `/debug` route from M1. Gate the dev-token panel behind `VITE_ENABLE_DEV_TOKEN`.
* **Task 9.7:** Cut `0.1.0` from `[Unreleased]` in the client CHANGELOG (created at this milestone). Tag the commit `client-v0.1.0`.

Tests for M9:
* Lighthouse CI run: scores Performance ≥ 85, Accessibility ≥ 95.
* Vitest spec for `logger`: includes `traceId`, `playerId`, `sessionId` on all messages emitted during a mock spin flow.
* Bundle size check: gzipped initial chunk ≤ 350 KB excluding the deferred Pixi chunk.

Foundation guarantee for production handoff: the client is observable, accessible, and within the bundle budget.

---

## Testing & Validation Requirements

You must include clean, expressive component, integration, and E2E tests matching the following criteria:

1. **API Wrapper Contract Tests:** every wrapper in `src/api/**` is tested with MSW handlers returning the canonical A.7 / A.8 / A.0.1 fixtures. The wrapper must produce the typed result with no `any` and must propagate every `ErrorCode` as a typed `RgsHttpError`.
2. **Session Store Tests:** parameterized tests covering every (`GameState`, server response) → new store snapshot transition. Stale `sessionVersion` paths assert the store ignores out-of-order responses.
3. **Mutation Idempotency Tests:** repeated user clicks (rapid double-tap on Spin) MUST reuse the same `Idempotency-Key` only when retrying a *failed* network request; a *new* user-initiated click generates a fresh key. Vitest harness asserts MSW receives exactly the expected key count.
4. **Pixi Rendering Tests:** stub `PIXI.Texture` and assert `SlotGrid.renderMatrix(matrix)` produces 15 sprite children with the expected texture references. Pure pixel diffs are NOT in scope.
5. **Feature Flow E2E (Playwright):** full demo journey — dev-token → init → spin → free-spin trigger → start → loop → settlement → bonus buy → pick & collect → settlement → balance reconciled, asserting both HUD numbers and the network log.
6. **Resume Flow E2E:** start a Pick & Collect feature, reload the page mid-feature, assert the board re-hydrates from `activeFeatureView` and the next pick succeeds.
7. **Error UX Tests:** induced `INSUFFICIENT_FUNDS`, `BONUS_BUY_DISABLED`, `MAX_WIN_REACHED`, `SESSION_VERSION_CONFLICT`, `ILLEGAL_STATE_TRANSITION` each produce the correct toast/modal and the correct recovery action.
8. **Accessibility Tests:** `axe-core` automated audit on each route. Manual keyboard-only walkthrough of the base spin loop and Pick & Collect.

---

## Hard Rule — Do Not Invent

The following items are fixed by the backend and the OpenAPI snapshot. The client MUST NOT rename, restructure, or substitute them:

* Endpoint paths, HTTP methods, header names (`Authorization`, `Idempotency-Key`, `X-Trace-Id`).
* Request/response field names and types (sourced from `client/openapi/openapi.yaml`).
* Enum names and values: `GameState`, `GameCommand`, `WalletTransactionType`, `RollbackReason`, `BonusBuyType`, `PickTileType`, `WalletTransactionStatus`, `ErrorCode`.
* Error codes and their HTTP status mapping.
* The semantic that the client never decides outcomes (`SPIN`, `PICK`, feature transitions are all server-authoritative).

If a client requirement seems to need a contract change, raise it in `client/openapi/gap-report.md` and request a backend change rather than diverging.

---

## Appendix B: Normative Defaults

### B.1 Folder Layout

```
client
├── openapi/
│   ├── openapi.yaml             # Mirrored from server/docs/openapi.yaml
│   └── gap-report.md            # Gaps between client needs and current contract
├── public/
│   └── assets/                  # Symbol sprites, atlas textures, audio
├── src/
│   ├── app/                     # App root, providers, router
│   ├── api/
│   │   ├── generated/openapi.ts # Generated from openapi.yaml — DO NOT EDIT
│   │   ├── http/                # axios instance, interceptors, error mapping
│   │   ├── slot/                # init.ts, spin.ts, featureStart.ts, featureBuy.ts, featurePick.ts
│   │   ├── wallet/              # authenticate.ts, balance.ts, debit.ts, credit.ts, rollback.ts
│   │   ├── admin/               # replay.ts, setBalance.ts, getSession.ts, getRound.ts, simulatorRun.ts
│   │   ├── dev/                 # token.ts (demo profile only)
│   │   └── enums.ts             # GameState, GameCommand, ErrorCode, etc.
│   ├── auth/                    # authStore, dev-token panel
│   ├── session/                 # sessionStore, useSessionInit, useSessionRecovery
│   ├── wallet/                  # walletStore, components/BalancePanel, components/BetSelector
│   ├── game/
│   │   ├── pixi/                # PixiApp, usePixiApp, assets, Reel, SlotGrid, SlotStage, SpinAnimator
│   │   ├── ui/                  # SpinButton, PowerBetToggle, reason-code banners
│   │   ├── spin/                # useSpin
│   │   ├── feature/
│   │   │   ├── freespins/
│   │   │   ├── bonusbuy/
│   │   │   └── pickcollect/     # PickBoard, PickBoardScene, useFeaturePick
│   │   └── math/                # Client-side math metadata mirrors (symbol names, paylines)
│   ├── common/
│   │   ├── money/               # Money value object
│   │   ├── idempotency/         # newIdempotencyKey, IdempotentMutation
│   │   └── ids/                 # uuid wrappers
│   ├── observability/           # logger, traceId provider, web-vitals
│   ├── i18n/                    # reason code translations, error message translations
│   ├── mocks/                   # MSW handlers, browser worker
│   ├── pages/                   # LobbyPage, PlayPage, AdminPage, AuthPage
│   └── styles/                  # Global CSS, design tokens
├── tests/
│   ├── e2e/                     # Playwright specs
│   └── fixtures/                # canonical JSON samples copied from be-requirements A.7
├── .env.example
├── index.html
├── package.json
├── pnpm-lock.yaml
├── playwright.config.ts
├── tsconfig.json
├── vite.config.ts
└── vitest.config.ts
```

### B.2 Environment Variables

| Name | Required | Purpose |
|---|---|---|
| `VITE_API_BASE_URL` | yes | RGS HTTP base URL, e.g. `http://localhost:8080` |
| `VITE_DEFAULT_GAME_ID` | yes | Default `gameId` for `/init`, e.g. `aztec-fire` |
| `VITE_DEFAULT_CURRENCY` | yes | Default currency: `EUR` or `USD` |
| `VITE_ENABLE_DEV_TOKEN` | no | `true` to expose the demo dev-token panel |
| `VITE_ENABLE_MSW` | no | `true` to run with MSW network mocks instead of the real backend |
| `VITE_LOG_SINK_URL` | no | If set, structured logs POST to this URL |

### B.3 Idempotency-Key Lifecycle (Authoritative)

* A key is generated by the **caller of the mutation** (the React mutation hook), not by the axios interceptor.
* The key is captured in a `useRef` for the lifetime of one user intent.
* Retries triggered by transport failure reuse the same key.
* A *new* user-initiated click MUST generate a fresh key.
* On `IDEMPOTENCY_KEY_CONFLICT` (409) the client logs at `ERROR` with both the key and the conflict reason from `details`; this is treated as a developer bug and surfaces a generic "Game communication error" modal.

### B.4 Reason Code → Human String Mapping (Initial Set)

| Reason Code | Banner Copy |
|---|---|
| `TRIGGERED_BY_SCATTER` | "Free Spins triggered!" |
| `RETRIGGERED_FREE_SPINS` | "+N Free Spins!" |
| `ENTERED_VIA_BUY` | "Feature purchased" |
| `MAX_WIN_CAPPED` | "Max win reached" |
| `PICK_COMPLETED` | "Pick & Collect complete!" |

Unknown reason codes render as the raw code in non-production profiles and are dropped in production.

### B.5 Backend Contract Summary (Reference Only)

| Endpoint | Method | Idempotent | Auth | Notes |
|---|---|---|---|---|
| `/api/v1/dev/token` | POST | no | none | demo profile only |
| `/api/v1/wallet/authenticate` | POST | no | JWT | request: `{playerId}` |
| `/api/v1/wallet/balance` | GET | n/a | JWT | response: `{playerId, balance, currency}` |
| `/api/v1/wallet/debit` | POST | yes | JWT | header `Idempotency-Key` mandatory |
| `/api/v1/wallet/credit` | POST | yes | JWT | header `Idempotency-Key` mandatory |
| `/api/v1/wallet/rollback` | POST | yes | JWT | header `Idempotency-Key` mandatory |
| `/api/v1/slot/init` | POST | no | JWT | request: `{gameId, currency}` |
| `/api/v1/slot/spin` | POST | yes | JWT | request: `{gameId, sessionId, sessionVersion, betSize, powerBetActive}` |
| `/api/v1/slot/feature/start` | POST | yes | JWT | `featureType ∈ FREE_SPINS \| PICK_COLLECT` |
| `/api/v1/slot/feature/buy` | POST | yes | JWT | `buyType ∈ FREE_SPINS_BUY \| PICK_COLLECT_BUY` |
| `/api/v1/slot/feature/pick` | POST | yes | JWT | request: `{gameId, sessionId, sessionVersion, position}` |
| `/api/v1/admin/replay/{roundId}` | POST | no | JWT (ADMIN) | M6 |
| `/api/v1/admin/wallet/balance` | POST | no | JWT (ADMIN) | demo profile only |
| `/api/v1/admin/session/{playerId}` | GET | n/a | JWT (ADMIN) | demo profile only |
| `/api/v1/admin/round/{roundId}` | GET | n/a | JWT (ADMIN) | demo profile only |
| `/api/v1/admin/simulator/run` | POST | no | JWT (ADMIN) | simulator/demo/test profiles |

All mutating endpoints accept `Idempotency-Key: <uuid>` and `X-Trace-Id: <uuid>` headers; the latter is also echoed back on every response.

### B.6 Definition of Done (per milestone)

A milestone is "done" only when:
1. `pnpm verify` (lint + typecheck + unit tests + production build) passes.
2. Test coverage on changed packages ≥ 80% lines (Vitest + c8).
3. New API consumption matches the committed `client/openapi/openapi.yaml`; `pnpm api:gen` produces no diff.
4. No `any`, no `// @ts-ignore`, no `// eslint-disable` left in committed code without an attached `TODO(name)` and a CHANGELOG note.
5. README is NOT auto-updated (per repo instructions); CHANGELOG entry under `## [Unreleased]` is mandatory from M9 onwards.