# Changelog

All notable changes to this project are documented here.

## [Unreleased]

### Pick & Collect — organic trigger only, real END-tile risk (2026-06)

- **Removed the Pick & Collect buy.** A fixed-price buy (`PICK_COLLECT_BUY`, 120–200× bet) into a feature whose standalone RTP is intentionally high made it a guaranteed-profit exploit — the simulator showed ~188% buy RTP. The buy option was dropped from all three games' `bonusBuyOptions`, the `BONUS_BUY_PICK_COLLECT` simulator channel and `spinsBonusBuyPickCollect` request field were removed, and the client's "Buy Pick & Collect" button is gone. (`BonusBuyType.PICK_COLLECT_BUY` is kept only so historical purchase rows still deserialize.)
- **Pick & Collect is now triggered organically** from a base/power spin via a configurable `pickCollect.triggerOneInN` (drawn from the spin RNG, so it stays replay-deterministic). Per game: aztec-fire 1-in-433, frost-crown 1-in-369, inferno-riches 1-in-571.
- **Real bank-vs-risk mechanic.** Completion switched from `FIXED_PICKS` to `END_TILE`: the player keeps revealing tiles until an `END` tile is hit, which **forfeits the unbanked pot** — only amounts banked via a `COLLECT` tile survive. The inert `BLANK` tiles were removed. Volatility is shaped per game (frost-crown: 44% bust / consistent; inferno-riches: 79% bust / boom-or-bust).
- **Rebalanced base pay tables** so each game's total base-game RTP stays at 96% with the new feature folded in: every pay-table coefficient scaled by `(96 − featureContribution) / measuredLineRtp` (≈4% feature contribution each). Verified via `RtpSimulationVerificationTest` over 2M spins (aztec 95.93%, frost 96.26%, inferno 96.28% — all within 0.6pp).
- **Widened the RTP simulator panel** (sidebar 360px → 480px) and right-aligned the numeric columns with tabular figures so large totals fit without overflowing the panel.

### RTP Tuning & Per-Line Bet Model

- **Fixed critical RTP inflation bug in `ReelEvaluator`:** The payout model was incorrectly paying `bet × coefficient` on **every payline independently**, inflating the effective RTP ~20×. The engine now implements the standard fixed-payline model: `lineBet = bet ÷ paylines`, and each line payout is `lineBet × payTableCoefficient`. This dramatically reduces per-spin variance (~20×) and makes RTP convergence tests tractable.
- **Added `targetRtp` field to `SlotMathDefinition`**: All game math now declares its intended RTP directly in the JSON (`math/<gameId>/<mathVersion>.json`). This is validated at load time and available for compliance reporting.
- **Retuned aztec-fire v1.json to 96% RTP**:
  - Paytable redesigned analytically (higher coefficients across all symbols).
  - BASE, POWER_BET, and FREE_SPINS reel strips optimized via analytical tuner to converge to the target RTP (verified: 95.9% over 1M base spins).
  - Bonus-buy costs realigned to fair RTP for each channel: Free-spins purchase `80× → 9×`, Pick & Collect purchase `120× → 155×`.
- **Updated `RtpSimulationService`** to read bonus-buy costs from the math definition instead of hardcoded constants, enabling per-game cost customization.
- **Added `@Tag("slow")` RTP verification test** (`RtpSimulationVerificationTest`): Runs 1,000,000 base-game spins and asserts the simulated RTP is within ±0.5pp of the declared target. Tagged as slow; excluded from `mvn test` and `mvn verify` by default. Run explicitly with `mvn -Prtp test` (~14s).
- **Updated Maven surefire configuration** to exclude the `slow` test group by default. Added `rtp` profile to run only slow statistical tests (`mvn -Prtp test`).
- **Updated all affected unit and integration tests** to account for the new per-line bet scaling (tests now use `bet=3.00` to keep line payouts in expected ranges) and the new bonus-buy costs.

### Verified Results

| Channel | Simulated RTP (300k+ spins) |
|---|---|
| Base Game (declared 96.0%) | 95.9% |
| Free-spins Purchase | 95.1% |
| Pick & Collect Purchase | 95.6% |

## [0.1.0] - QA-ready milestone (M7)

### Milestone 7 — QA Readiness & Operational Tooling

- Added top-level `docker-compose.yml` (Postgres 16 + Redis 7 with healthchecks and a named volume) and per-repo `README.md` (root) + `server/README.md` covering prerequisites, quickstart, environment variables, profile matrix, build/test/package commands.
- Added demo-only `qa.dev.DevTokenController` (`@Profile("demo")`) exposing `POST /api/v1/dev/token` that mints HS256 JWTs (subject, sid, cur, roles, issuer from `SecurityProperties`); the request DTO validates ISO currency codes and clamps `ttlMinutes` defaults; the endpoint path is whitelisted in `application-demo.yml` via `rgs.security.public-paths` so callers without a token can self-bootstrap.
- Added demo-only `qa.admin.AdminQaController` (`@Profile("demo")`, all endpoints require the JWT `ADMIN` role claim):
  - `POST /api/v1/admin/wallet/balance` — upserts the `wallet_balance` row for an arbitrary `(playerId, currency)` pair using the `Money` helper for minor-unit conversion; enforces currency stability on existing rows; writes a structured audit log line.
  - `GET /api/v1/admin/session/{playerId}` — returns the persistent `GameSession` projection plus a `cachedInRedis` flag and the parsed `active_feature_payload` JSON.
  - `GET /api/v1/admin/round/{roundId}` — returns the full persisted `GameRound` (matrix, stop positions, RNG draws, win lines, reason codes) for offline manual replay or compliance review.
- Added the synchronous RTP simulator HTTP harness per A.19 / Task 7.6:
  - Refactored the existing `RtpSimulator` CLI runner so all simulation logic now lives in a reusable `game.service.RtpSimulationService`; the CLI is a thin `CommandLineRunner` wrapper that builds a default `RtpSimulationRequest` from `rgs.simulator.*` properties.
  - `game.service.RtpSimulationRequest` (record with validation) parameterises the run with `gameId`, `mathVersion`, `bet`, three independent spin counts (`spinsBaseGame`, `spinsBonusBuyFreeSpins`, `spinsBonusBuyPickCollect`), and a `PickStrategy` enum (`SEQUENTIAL`, `RANDOM_UNOPENED`, `COLLECT_FIRST`); `game.service.RtpReport` carries per-channel `Channel(spins, totalBet, totalWin, rtpPercent)` plus a wager-weighted overall channel and elapsed time.
  - `qa.simulator.SimulatorAdminController` — `@ConditionalOnExpression` gating exposes `POST /api/v1/admin/simulator/run` under the `simulator` profile and (when `rgs.simulator.expose-on-demo=true`) under `demo`. ADMIN-guarded; persists each invocation into `audit_simulation_run` with the request JSON, the report JSON, and the start/finish timestamps so compliance can cite a stable `runId`.
  - Flyway `V9__audit_simulation_run.sql` migration + `audit.simulation.AuditSimulationRun` JPA entity (JSONB `params` / `report` columns) + `AuditSimulationRunRepository`.
  - `application-demo.yml` sets `rgs.simulator.expose-on-demo=true` so QA can drive simulations from the demo deployment without a separate profile.
- Hardened actuator under the `wallet-operator` profile: `application-wallet-operator.yml` now sets `rgs.security.public-paths` to only the liveness and readiness probes; `/actuator/prometheus`, `/actuator/info`, `/v3/api-docs`, and Swagger UI all require a valid JWT in production deployments.

### Documentation (Milestone 7)

- Added [`README.md`](README.md) at the repository root. The `be-requirements.md` blueprint received `Milestone 7 — QA Readiness & Operational Tooling` (Section 7), `Appendix A.19 — RTP Simulator HTTP API`, and `Appendix A.20 — Dev & QA Helpers` ahead of implementation.

### Deferred from Milestone 7

- **Task 7.5 (OpenAPI commit gate):** plumbing is wired (`springdoc-openapi-maven-plugin` + Spring Boot start/stop bound to `integration-test`), but the default `skip.openapi.gen=true` was kept to avoid breaking CI before the initial `docs/openapi.yaml` snapshot is committed. Follow-up ticket: flip the default to `false`, commit the generated spec, and add a `git diff --exit-code docs/openapi.yaml` step to `.github/workflows/server-ci.yml`.

### Milestone 6 — Audit, Replay & Reconciliation Hardening

- Added Flyway migration `V8__audit_reconciliation_finding.sql` per A.16: `audit_reconciliation_finding` table with `(player_id, bucket_start, discrepancy_kind)` UNIQUE for idempotent re-runs, `bucket_start`/`bucket_end` TIMESTAMPTZ, `expected_debit`/`actual_debit`/`expected_credit`/`actual_credit`/`discrepancy` NUMERIC(19,4), `discrepancy_kind`, free-text `detail`, plus `(player_id, created_at)` and `(bucket_start)` indexes.
- Implemented `audit.AuditReconciliationFinding` JPA entity (`@Builder`, nested `DiscrepancyKind { DEBIT_MISMATCH, CREDIT_MISMATCH }`) + `AuditReconciliationFindingRepository` (Spring Data) exposing `existsByPlayerIdAndBucketStartAndDiscrepancyKind`, `findByPlayerIdOrderByCreatedAtDesc`, and bucket lookup queries.
- Implemented bit-exact round replay per A.16 / Task 6.2:
  - `audit.replay.ReplayService` (`@Service`, `@Transactional(readOnly=true)`) loads the persisted `GameRound`, deserialises `rng_draws` / `matrix` / `stop_positions`, infers the originating `ReelStripSet` (`FREE_SPINS` when `bet_transaction_id` is null, else `POWER_BET` / `BASE` based on `power_bet_active`), runs `DeterministicReplayRng` through `GridGenerationEngine` + `ReelEvaluator`, and asserts the reconstructed matrix + stop positions are byte-identical (`Arrays.deepEquals` / `Arrays.equals`) — divergence throws `INTERNAL_ERROR` with a full log dump.
  - `audit.replay.RoundReplayResult` record carries original vs reconstructed matrix/stops/totalWin + reel strip set name + `matrixMatches` and `totalWinMatches` flags for the admin caller.
  - `audit.replay.ReplayAdminController` exposes `POST /api/v1/admin/replay/{roundId}` guarded by the JWT `roles` claim (`PlayerContext.hasRole("ADMIN")`); non-admin → `FORBIDDEN_ACTION` (HTTP 403), unknown round → `SESSION_NOT_FOUND` (HTTP 404).
- Implemented hourly reconciliation per A.16 / Task 6.3:
  - `audit.reconciliation.ReconciliationJob` (`@Component`, `@Scheduled(cron = "${rgs.audit.reconciliation.cron:0 5 * * * *}")`) — runs at minute 5 of every hour over the just-closed hour bucket. Public `runForBucket(start, end)` is invocable directly so ops/tests can replay an arbitrary window. Expected debits = `sum(game_round.bet_amount where bet_transaction_id is not null) + sum(feature_purchase_event.cost)`; actual debits = `sum(wallet_transaction.amount where type in BET, BONUS_BUY)` minus rollbacks whose original transaction was a debit; expected credits = `sum(game_round.total_win where win_transaction_id is not null)`; actual credits = `sum(wallet_transaction.amount where type in WIN, FEATURE_WIN)` minus rollbacks whose original transaction was a credit. Each mismatched player gets a `DEBIT_MISMATCH` or `CREDIT_MISMATCH` row, logged at `ERROR`, and increments the `rgs.reconciliation.discrepancy` Micrometer counter (registered in `@PostConstruct`). Idempotent re-runs are no-ops thanks to the unique constraint.
  - `audit.reconciliation.ReconciliationQueryRepository` (custom `EntityManager`-backed) holds the five aggregation JPQL queries (game-round bets, game-round wins, feature-purchase costs, wallet sums by type, rollback sums joined to original transaction types).
  - `audit.reconciliation.ReconciliationAggregate` record carries `(playerId, currency, totalAmount)` projections with a `orZero` null-guard.
- Added `@EnableScheduling` to `VelocityRgsApplication` so the cron actually fires (was previously only used implicitly by Spring Data).
- Implemented per-pick audit fan-out per Section 5 Implementation Notes / Task 6.5:
  - `audit.pickaudit.PickAuditEvent` record carries `playerId`, `sessionId`, `position`, resolved tile type + value, deterministic SHA-256 `beforeStateHash` / `afterStateHash`, before/after `currentCollected` / `totalFeatureWin` / `remainingPicks`, `featureCompleted`, `occurredAt`.
  - `audit.pickaudit.PickCollectStateHasher` produces a deterministic SHA-256 fingerprint over a canonical field-ordered string (board size, opened positions, current collected, total feature win, remaining picks, status, full tile sequence) so the auditor can prove engine-snapshot equivalence.
  - `audit.pickaudit.PickAuditEventListener` (`@Component`) logs every event at INFO with all before/after deltas for downstream SIEM ingestion.
  - `game.service.SlotEngineService.pickFeature` now captures the pre-pick state hash + scalars, applies the pick, then publishes the `PickAuditEvent` via Spring's `ApplicationEventPublisher` after the session is saved.
- Implemented full operator wallet integration per Task 6.4 (`wallet-operator` profile):
  - `wallet.gateway.OperatorWalletProperties` bound at `rgs.wallet.operator.*` (`baseUrl`, `timeoutMs` default 2000 ms, optional `authToken` for the upstream `Bearer` header).
  - `wallet.gateway.OperatorWalletConfiguration` (`@Profile("wallet-operator")`) exposes a tuned `WebClient` bean: Reactor-Netty `HttpClient` with `CONNECT_TIMEOUT_MILLIS` + `ReadTimeoutHandler` + `WriteTimeoutHandler`, default JSON `Content-Type` + `Accept` headers, optional `Bearer` auth header, plus an `ExchangeFilterFunction` that parses error bodies as `ApiError` and maps the upstream `code` to `ErrorCode.valueOf(...)`; falls back to a status-based map (401 → `AUTH_FAILED`, 403 → `FORBIDDEN_ACTION`, 404 → `ORIGINAL_TRANSACTION_NOT_FOUND`, 409 → `DUPLICATE_TRANSACTION`, other 4xx → `VALIDATION_ERROR`, anything else → `INTERNAL_ERROR`). Errors short-circuit downstream `bodyToMono` via `Mono.error(RgsException)`.
  - `wallet.gateway.OperatorWalletGateway` reimplemented from the not-yet-implemented skeleton: blocking `WebClient` calls with the configured per-call timeout, forwards the `Idempotency-Key` HTTP header on every mutating call (`authenticate`, `debit`, `credit`, `rollback`) so the upstream wallet's server-side replay store de-dupes retries identically to the in-process flow; preserves `RgsException`s thrown by the filter chain, maps `WebClientRequestException` (network / DNS / timeout) to `INTERNAL_ERROR`.
- Added `org.springframework.boot:spring-boot-starter-webflux` (transitive `WebClient`) and `org.wiremock:wiremock-standalone:3.9.2` (test scope) to `pom.xml`.
- Pinned `spring.main.web-application-type=servlet` in `application.yml` so the added WebFlux starter never accidentally flips the runtime to a reactive Netty server.

### Tests (Milestone 6)

- `ReplayAdminControllerIntegrationTest` (`@RgsIntegrationTest`, 3 cases): `/api/v1/admin/replay/{roundId}` with a real spin → reconstructed matrix + total win byte-match the stored round; non-admin JWT → HTTP 403 with `FORBIDDEN_ACTION`; unknown roundId → HTTP 404 with `SESSION_NOT_FOUND`.
- `ReconciliationJobIntegrationTest` (`@RgsIntegrationTest`, 5 cases): expected-vs-actual debit mismatch produces a `DEBIT_MISMATCH` finding and increments the `rgs.reconciliation.discrepancy` counter by exactly 1; credit mismatch produces a `CREDIT_MISMATCH` finding with the correct delta; a fully reconciled player produces zero findings; a `ROLLBACK` row whose original `BET` matches it cancels the debit (no false positive); repeated `runForBucket` calls are idempotent (UNIQUE on `(player_id, bucket_start, discrepancy_kind)` prevents duplicate rows).
- `OperatorWalletGatewayWiremockTest` (6 pure unit cases, no Spring context): WireMock dynamic-port server stubs each wallet endpoint; verifies request parsing for `authenticate` / `balance` / `debit`, asserts the `Idempotency-Key` header is forwarded verbatim, and verifies the error-translating filter maps `404 + ORIGINAL_TRANSACTION_NOT_FOUND` body, `409 + DUPLICATE_TRANSACTION` body, and a bodyless `500` to the canonical `ErrorCode`s.
- `PickAuditEventIntegrationTest` (`@RgsIntegrationTest`, `@Import` test `CapturingPickAuditListener`): drives a full Pick & Collect journey (`/feature/buy PICK_COLLECT_BUY → /feature/start → /feature/pick × n`); asserts every pick emits a `PickAuditEvent` with non-blank 64-hex `beforeStateHash` / `afterStateHash` that differ between calls, `remainingPicksAfter ≤ remainingPicksBefore`, and the last event carries `featureCompleted=true`.

### Milestone 5 — Slot Game API & End-to-End Wiring

- Added Flyway migrations per A.9:
  - `V5__game_round.sql` — per-spin audit row with `round_id` UNIQUE, monetary `bet_amount`/`total_win` (NUMERIC(19,4)) mirrored to `BIGINT` minor units, `final_grid JSONB`, `rng_draws JSONB`, `win_lines JSONB`, `reason_codes JSONB`, `bet_transaction_id` / `win_transaction_id`, `power_bet_active`, plus `(session_id, created_at)` and `(player_id, created_at)` indexes.
  - `V6__feature_purchase_event.sql` — immutable bonus-buy ledger with `(player_id, transaction_id)` UNIQUE, `buy_type`, `cost_minor`, `target_state`, `initial_feature_payload JSONB`, indexes on `session_id` and `(player_id, created_at)`.
  - `V7__pick_collect_snapshot.sql` — Pick & Collect feature snapshot keyed by `(session_id, round_id)`, `board JSONB`, `opened_positions JSONB`, `board_seed`, monetary `final_win` mirrored to `BIGINT` minor units, `status`.
- Implemented JPA entities + Spring Data repositories per Task 5.3:
  - `game.persistence.GameRound` + `GameRoundRepository` (`findByRoundId`, `findBySessionIdOrderByCreatedAtDesc`).
  - `game.persistence.FeaturePurchaseEvent` + `FeaturePurchaseEventRepository` (`findByPlayerIdAndTransactionId`).
  - `game.persistence.PickCollectSnapshot` + `PickCollectSnapshotRepository` (`findFirstBySessionIdOrderByCreatedAtDesc`).
- Implemented `game.feature.pickcollect.PickCollectEngine` (`@Component`) per Task 5.5:
  - `PickCollectTile(type, value)` record + `PickCollectState` mutable holder + `PickCollectFeatureView` projection.
  - `startFeature(config, betSize, rng, initialRemainingPicks)` builds the immutable tile board by weighted RNG draws (`CREDITS` / `MULTIPLIER` / `COLLECT` / `BLANK` / `END`); CREDITS/MULTIPLIER values are rolled inside the config's `valueRange`.
  - `applyPick(state, position, config)` enforces position bounds and no-duplicate-pick (`VALIDATION_ERROR`), `ILLEGAL_STATE_TRANSITION` when already completed or out of picks; CREDITS adds to `currentCollected`, MULTIPLIER multiplies `currentCollected`, COLLECT banks `currentCollected → totalFeatureWin` and resets, BLANK is a no-op, END terminates immediately. Returns `PickResolution(resolvedTileType, resolvedValue, reasonCodes)`.
  - `finalizeFeature(state, config, currency)` returns `FinalizationResult(finalWin, reasonCodes)` = `(totalFeatureWin + currentCollected) * betSize`, capped at `betSize * maxFeatureWinMultiplier` (`MAX_WIN_CAPPED` reason code), normalised to the currency's minor-unit scale.
  - Completion rules: `FIXED_PICKS`, `END_TILE`, `COLLECT_THRESHOLD`.
- Implemented `game.feature.bonusbuy.BonusBuyPolicyService` (`@Service`) per Task 5.6:
  - `requireOption(math, buyType, session, balance, betSize, jurisdiction)` enforces the global kill-switch and jurisdiction allow-list (`BONUS_BUY_DISABLED`), BASE_GAME-only precondition (`ILLEGAL_STATE_TRANSITION`), upfront balance check against `betSize × costMultiplier` (`INSUFFICIENT_FUNDS`).
  - `BonusBuyPolicyProperties` bound at `rgs.bonus-buy.*` (`enabled` defaults to true, `allowedJurisdictions` empty = unrestricted, `minimumBalance` 0).
- Implemented Slot Game public DTOs (records) under `game.api.*` per A.7: `SlotInitRequest/Response`, `SpinRequest/Response` (with nested `FeaturesTriggered` and `SessionStateView` records), `FeatureStartRequest/Response`, `FeatureBuyRequest/Response`, `FeaturePickRequest/Response`. All mutating requests carry validation constraints; all responses are `@Builder` records with `@JsonInclude(NON_NULL)`.
- Implemented `game.service.SlotEngineService` (`@Service`, `@Transactional` per method) per Task 5.8 — the single orchestrator behind every public Slot endpoint:
  - `init` — resumes the player's latest matching session or creates a fresh `BASE_GAME` one with a generated `ses-…` id; checks wallet eligibility and returns the canonical session snapshot + `availableActions` + feature flags + active Pick & Collect view (if any).
  - `spin` — acquires `PlayerActionLock`, loads + version-checks the session, runs the FSM `SpinCommand`, then `GridGenerationEngine` + `ReelEvaluator` with a per-round `SecureRandomNumberGenerator`, debits the bet (`{roundId}:bet` txId) and credits any payout (`{roundId}:win` txId), enforces free-spin `betLockedToTriggerBet` semantics, detects SCATTER triggers and transitions to `FREE_SPINS_AWAITING` with `freeSpinsAwarded`, decrements free-spin counter inside the loop and credits accumulated free-spin winnings on termination, persists the `GameRound` row, saves the session and releases the lock.
  - `startFeature` — atomic AWAITING→LOOP transition for both FREE_SPINS and PICK_COLLECT; bootstraps `PickCollectState` from the BUY/SCATTER initial payload and serialises it back into `active_feature_payload` JSONB.
  - `buyFeature` — bonus-buy entry point: delegates jurisdiction/balance/state checks to `BonusBuyPolicyService`, debits cost via wallet (`{roundId}:bonus-buy` txId), persists `FeaturePurchaseEvent`, and transitions session to the option's `targetState`.
  - `pickFeature` — single Pick & Collect interaction: runs `PickCollectEngine.applyPick`, on completion calls `finalizeFeature`, credits the feature win to wallet (`pick-{sessionId}-{ts}` txId), persists / updates the `PickCollectSnapshot`, and returns `BASE_GAME`.
  - All wallet effects use deterministic `transactionId`s derived from `roundId` so a client retry naturally collides on the wallet's `DUPLICATE_TRANSACTION` guard; idempotency replays are short-circuited upstream by `IdempotencyAspect` before the service is ever entered.
- Implemented `game.api.SlotGameController` (`@RestController`, `/api/v1/slot/*`) per Task 5.9 — five `POST` endpoints:
  - `POST /init` (no idempotency key required; resumable).
  - `POST /spin`, `POST /feature/start`, `POST /feature/buy`, `POST /feature/pick` — all carry `@Idempotent` with `slot:<action>:{playerId}` scope and a 24h TTL; authenticated player is taken from `PlayerContext` (`AUTH_FAILED` if absent).
- Implemented `game.service.RtpSimulator` (`@Component` under `simulator` profile) per Task 5.7 — a `CommandLineRunner` that executes `rgs.simulator.spins` base spins plus `spins/50` bonus-buy rounds against the real engine pipeline, then prints `BASE_GAME_RTP`, `BONUS_BUY_RTP`, and `OVERALL_RTP` percentages. Useful for math-tuning validation outside the live API.
- Fixed a Pick & Collect monetary-scale bug: when serialising `PickCollectSnapshot.finalWinMinor`, the running `currentCollected` may carry the engine's intermediate scale (4) after a MULTIPLIER tile, which combined with `betSize` overflowed the currency's minor-unit scale (2 for EUR/USD) and tripped `Money.of`'s scale guard. The snapshot writer now normalises to `Money.minorUnitScale(currency)` with HALF_UP rounding before constructing the `Money` value.

### Tests (Milestone 5)

- `PickCollectEngineTest` (4 pure unit cases): startFeature produces an immutable board of the requested size, duplicate pick on the same position raises `VALIDATION_ERROR`, CREDITS+MULTIPLIER+COLLECT math accumulates correctly with deterministic RNG draws, completion flips status to `COMPLETED` and rejects further picks.
- `BonusBuyPolicyServiceTest` (5 unit cases, loads real `aztec-fire/v1` math): globally-disabled kill-switch raises `BONUS_BUY_DISABLED`, jurisdiction-not-in-allowlist raises `BONUS_BUY_DISABLED`, non-`BASE_GAME` session raises `ILLEGAL_STATE_TRANSITION`, balance below `betSize × costMultiplier` raises `INSUFFICIENT_FUNDS`, all-checks-pass returns the matching `BonusBuyOption` with the expected `targetState` and `costMultiplier`.
- `SlotGameControllerIntegrationTest` (`@RgsIntegrationTest`, full Testcontainers Postgres + Redis, 7 end-to-end cases): `/init` creates a fresh `BASE_GAME` session with `availableActions` + feature flags + seeded demo balance; `/init` is resumable (second call returns the same `sessionId`); `/spin` debits the bet and persists a `GameRound` row plus the wallet `:bet` ledger entry; replayed `/spin` with the same `Idempotency-Key` returns the byte-identical cached body with `Idempotent-Replay: true` and creates exactly one round; `/feature/buy` debits the bonus-buy cost, persists a `FeaturePurchaseEvent`, and advances state to `FREE_SPINS_AWAITING`; full Pick & Collect journey (`/feature/buy` PICK_COLLECT_BUY → `/feature/start` PICK_COLLECT → repeated `/feature/pick`) completes back to `BASE_GAME` and writes a `COMPLETED` `PickCollectSnapshot`; missing `Idempotency-Key` on `/spin` returns `400 VALIDATION_ERROR`.

### Milestone 4 — Session FSM & Persistence

- Added Flyway migration `V4__game_session.sql` per A.9: `game_session` table with `session_id` UNIQUE, `current_state`, monetary `current_bet`/`accumulated_free_spins_win` (NUMERIC(19,4)), `active_feature_payload JSONB`, `next_action_allowed`, `session_version BIGINT` for JPA optimistic locking, and `(player_id, updated_at)` + `(player_id, game_id)` indexes.
- Added canonical `session.domain.GameCommand` enum (A.0.1: SPIN / START_FREE_SPINS / BUY_FEATURE / START_PICK_COLLECT / PICK).
- Implemented `session.domain.GameSession` JPA entity with `@Version` on `session_version` and `@JdbcTypeCode(SqlTypes.JSON)` on `activeFeaturePayload` (string-backed JSONB).
- Implemented sealed types per Task 4.3:
  - `session.fsm.SessionState` permits `BaseGame`, `FreeSpinsAwaiting(remainingFreeSpins, triggerBet)`, `FreeSpinsLoop(remainingFreeSpins, accumulatedWin, triggerBet)`, `PickCollectAwaiting(initialPayload)`, `PickCollectLoop(featurePayload)`; each variant exposes `gameState()` to project back to the persistent enum.
  - `session.fsm.SessionCommand` permits `SpinCommand(betSize, powerBetActive)`, `StartFreeSpinsCommand`, `BuyFeatureCommand(buyType, betSize)`, `StartPickCollectCommand`, `PickCommand(position)`.
- Implemented `session.fsm.MonetaryEffect` sealed hierarchy (`Debit` / `Credit` / `None`) carrying the `Money` amount and `WalletTransactionType` — wallet I/O is reserved for `SlotEngineService` (M5).
- Implemented `session.fsm.SessionStateMachine` (`@Component`) as the pure transition function `(SessionState, SessionCommand, TransitionContext) -> TransitionResult` per Task 4.4. Java 21 pattern-matching `switch` over the sealed hierarchy; no I/O, no wallet calls. Behaviour:
  - `BASE_GAME` + `SPIN` -> stays in `BASE_GAME` with a `Debit(bet, BET)` effect; `availableActions` reflects whether the catalog offers bonus buys.
  - `BASE_GAME` + `BUY_FEATURE` -> resolves the matching `BonusBuyOption`, emits `Debit(bet * costMultiplier, BONUS_BUY)`, transitions to the option's `targetState` (`FREE_SPINS_AWAITING` seeds `remainingFreeSpins` from `initialFeaturePayload.freeSpinsAwarded`; `PICK_COLLECT_AWAITING` carries the bootstrap payload), reason code `ENTERED_VIA_BUY`. Missing buy option -> `BONUS_BUY_DISABLED` (A.8).
  - `FREE_SPINS_AWAITING` + `START_FREE_SPINS` -> `FREE_SPINS_LOOP`, no effect.
  - `FREE_SPINS_LOOP` + `SPIN` -> stays in loop, no effect; enforces `freeSpins.betLockedToTriggerBet` and `freeSpins.powerBetPersists` per A.13 (mismatch -> `VALIDATION_ERROR`).
  - `PICK_COLLECT_AWAITING` + `START_PICK_COLLECT` -> `PICK_COLLECT_LOOP`, no effect.
  - `PICK_COLLECT_LOOP` + `PICK` -> stays in loop, no effect (per-pick resolution is the M5 `PickCollectEngine`).
  - Any other (state, command) pair -> `ILLEGAL_STATE_TRANSITION` (A.8, 409).
- Implemented `session.persistence.GameSessionRepository` (`JpaRepository`) with `findBySessionId(...)` and `findFirstByPlayerIdOrderByUpdatedAtDesc(...)`.
- Implemented `session.persistence.SessionCache` — Redis-backed JSON cache keyed by `rgs:session:{playerId}` with the A.10 default 30-minute TTL. Read/write failures fall back transparently to Postgres (per Persistence Rules).
- Implemented `session.service.SessionStore` façade per Task 4.6: reads check Redis first and rehydrate on miss; writes go through Postgres (`saveAndFlush`) then refresh Redis. Exposes `requireByPlayerId` / `requireBySessionId` raising `SESSION_NOT_FOUND` when absent.
- Implemented `session.service.PlayerActionLock` per Task 4.7 / A.10: per-player short-lived lock via Redis `SET NX PX <ttl>` keyed by `rgs:lock:player:{playerId}` (default 3s TTL). Owner-safe release via a Lua compare-and-delete script (`DefaultRedisScript`), opaque caller-owned token in `LockHandle`. `acquire(...)` raises `SESSION_VERSION_CONFLICT` (A.8, 409) when the key is already held; `tryAcquire(...)` returns an `Optional` for callers that want to back off non-fatally.

### Tests (Milestone 4)

- `SessionStateMachineTest` (pure unit, 31 cases): full legal-transition coverage (BASE_GAME spin debit + power-bet flag, BASE_GAME bonus buy → FREE_SPINS_AWAITING with `freeSpinsAwarded`-seeded remaining count and `Money.of("80.00","EUR")` debit, BASE_GAME bonus buy → PICK_COLLECT_AWAITING with `Money.of("120.00","EUR")` debit, START_FREE_SPINS, FREE_SPINS_LOOP spin with no effect, START_PICK_COLLECT, PICK_COLLECT_LOOP pick), guard rules (free-spin bet-locked mismatch and Power Bet during free spins → `VALIDATION_ERROR`), parameterized illegal-transition matrix across all 5 states × 5 commands → `ILLEGAL_STATE_TRANSITION`, `availableActions` reflects an empty bonus-buy catalog, unknown buy type → `BONUS_BUY_DISABLED`, `gameState()` projections.
- `SessionStoreIntegrationTest` (`@RgsIntegrationTest`, Testcontainers Postgres + Redis): `save(...)` writes Postgres and populates the cache; `findByPlayerId` hits cache before Postgres (verified by deleting the DB row and still serving from cache); cache-miss rehydrates from Postgres and repopulates Redis; `evict(...)` clears the cache but keeps the DB record; stale `session_version` write raises `OptimisticLockingFailureException` (mapped by `GlobalExceptionHandler` to `SESSION_VERSION_CONFLICT`).
- `PlayerActionLockIntegrationTest` (`@RgsIntegrationTest`, Testcontainers Redis): acquire writes a token at `rgs:lock:player:{playerId}` and release clears it; second `acquire` while the first holds raises `SESSION_VERSION_CONFLICT` and `tryAcquire` returns empty; release with a foreign token does not delete the key (Lua compare-and-delete); short-TTL lock expires and can be re-acquired.

### Milestone 3 — Wallet API, Gateway & Ledger

- Added Flyway migrations `V2__wallet_balance.sql` (per-player current balance with `version` for JPA optimistic locking) and `V3__wallet_transaction.sql` (immutable ledger with unique `transaction_id`, player/created and original-tx indexes per A.9).
- Added canonical wallet enums per A.0.1: `WalletTransactionType` (BET / BONUS_BUY / WIN / FEATURE_WIN / ROLLBACK), `WalletTransactionStatus` (SUCCESS / REJECTED), `RollbackReason` (DOWNSTREAM_FAILURE / TECHNICAL_ERROR / OPERATOR_CANCEL).
- Added DTO records for the wallet wire contract: `WalletAuthenticateRequest/Response`, `WalletBalanceResponse`, `WalletDebitRequest/Response`, `WalletCreditRequest/Response`, `WalletRollbackRequest/Response` — bodies match A.0.1 byte-for-byte (no `idempotencyKey` field; the key arrives via the `Idempotency-Key` header per A.6).
- Implemented JPA entities `WalletBalance` (optimistic `@Version`) and `WalletTransaction` (insert-only, monetary columns `NUMERIC(19,4)` plus mirrored `BIGINT` minor units per A.9) and their Spring Data repositories.
- Implemented `WalletLedgerWriter` (`REQUIRES_NEW` transactional unit) and `InternalWalletService` (orchestrator with bounded optimistic-lock retry loop) covering `authenticate`, `balance`, `debit`, `credit`, `rollback`. All math goes through `Money` with HALF_UP rounding; balance + ledger insert are committed atomically per call.
- Implemented `WalletGateway` interface plus `InternalWalletGateway` (active in `default` / `demo` / `wallet-internal` / `test` / `simulator`) and `OperatorWalletGateway` skeleton (active in `wallet-operator`; throws `INTERNAL_ERROR` with explicit M6 deferral message).
- Implemented `WalletController` under `/api/v1/wallet/*` (authenticate, balance, debit, credit, rollback). Mutating endpoints carry `@Idempotent` with the exact A.6 scope strings (`wallet:debit:{playerId}:{transactionId}`, `wallet:credit:{playerId}:{transactionId}`, `wallet:rollback:{playerId}:{originalTransactionId}`) and a 48h TTL. JWT-authenticated player is enforced against the request `playerId` (`FORBIDDEN_ACTION` on mismatch).
- Added `WalletDemoSeeder` (`@Profile({"demo","default","test","simulator"})`) that creates a `WalletBalance` row on first `authenticate` for an unknown player using the configured starting balance (default 10,000.00 EUR / 1,000,000 minor units).
- Added `WalletProperties` (`rgs.wallet.*`) and a small `WalletConfiguration` binding bean; wallet defaults are declared in `application.yml` so non-demo profiles inherit them.
- Mapped the full set of M3 wallet error codes per A.8: `INSUFFICIENT_FUNDS`, `DUPLICATE_TRANSACTION` (raised both upfront and via the unique-constraint catch in the ledger writer), `ORIGINAL_TRANSACTION_NOT_FOUND`, `CURRENCY_MISMATCH`, `FORBIDDEN_ACTION`, plus `SESSION_VERSION_CONFLICT` when balance-row contention exceeds the retry budget.

### Tests (Milestone 3)

- `WalletControllerIntegrationTest` (`@RgsIntegrationTest`, full Testcontainers Postgres + Redis): 13 end-to-end cases covering authenticate-with-auto-seed, balance, successful debit (balance + ledger asserted), idempotent-replay returns cached response with `Idempotent-Replay: true` and no extra ledger row, same `transactionId` with a different idempotency key raises `DUPLICATE_TRANSACTION`, insufficient funds rejects without state mutation, currency mismatch against JWT `cur`, full debit → credit → rollback ledger flow with balance reconciliation, unknown original-tx rollback returns `404`, second rollback on the same original raises `DUPLICATE_TRANSACTION`, two concurrent debits both succeed via optimistic-lock retry, unauthenticated request → `401`, `playerId` ≠ JWT principal → `403`.



- Added `rng.RngDraw` record (`boundExclusive`, `value`, `sequence`) — the canonical audit unit persisted into `game_round.rng_draws` per A.11.
- Added `rng.RandomNumberGenerator` round-scoped interface with single method `int nextIndex(int boundExclusive)` so production and replay paths are wire-compatible.
- Added `rng.RngDrawSink` (+ default `InMemoryRngDrawSink`) — the caller-supplied, per-round audit collector.
- Implemented `rng.SecureRandomNumberGenerator`: wraps `java.security.SecureRandom`, records every draw to the sink with monotonic per-instance sequence, rejects non-positive bounds. Stateful → instantiated per round (not a Spring bean).
- Implemented `rng.DeterministicReplayRng`: pops a pre-recorded `List<RngDraw>` in order, enforces bound parity with the original capture, throws when drained. Used by `ReplayService` (M6) and by every milestone's deterministic unit tests.
- Implemented `math.engine.GridGenerationEngine` (`@Component`): for a given `SlotMathDefinition` + `ReelStripSet` (`BASE` / `POWER_BET` / `FREE_SPINS`), draws one stop per reel and builds the `rows × cols` matrix via wrapping indexing. Returns immutable `GridGenerationResult(matrix, stopPositions)` wire-aligned with A.7's `stopPositions` field.

### Tests (Milestone 2)

- `SecureRandomNumberGeneratorTest`: bound respect across 5k draws, audit-list order + monotonic sequence, value parity with injected `SecureRandom`, non-positive bound rejection.
- `DeterministicReplayRngTest`: exact replay of a recorded sequence, drained-throws, bound-mismatch detection, full secure→replay round-trip.
- `GridGenerationEngineTest`: stop=0 yields first three symbols of each strip; stop near end-of-strip wraps to the strip head; reel-strip-set selector swaps the underlying strips (`BASE` vs `POWER_BET`); engine requests exactly one draw per reel at `boundExclusive = stripLength`.

### Milestone 1 — Math Domain & JSON Configuration

- Added `math.domain` records `Symbol`, `Payline`, `PayTable`, `ReelStrip` and enums `SymbolType`, `ReelStripSet`, `BonusBuyType`, `PickTileType`.
- Added canonical `session.domain.GameState` enum (A.0.1) referenced by `BonusBuyOption.targetState`.
- Implemented `math.config.SlotMathDefinition` plus nested records (`Grid`, `ScatterTriggers`, `FreeSpinsConfig`, `PowerBetConfig`, `BonusBuyOption`, `PickCollectConfig`, `PickCollectCompletion`, `PickTileWeight`, `Limits`) with compact-constructor structural validation (grid size, unique symbol/payline ids, single WILD + SCATTER, reel strip set completeness, coord bounds).
- Implemented `SlotMathLoader` (dedicated strict Jackson `ObjectMapper`: `FAIL_ON_UNKNOWN_PROPERTIES`, `USE_BIG_DECIMAL_FOR_FLOATS`, parameter names module) that reads `math/<gameId>/<mathVersion>.json` (A.4) and asserts header parity with the requested coordinates.
- Implemented `SlotMathRegistry` (immutable lookup) and `SlotMathConfiguration` (`@Configuration` + `@EnableConfigurationProperties`) that materializes every entry in the configured game catalog at startup.
- Added `MathCatalogProperties` (`rgs.math.catalog`) and seeded the catalog with `aztec-fire@v1` in `application.yml` (A.5).
- Shipped the reference math fixture `src/main/resources/math/aztec-fire/v1.json`: 10 symbols (8 STANDARD + WILD + SCATTER), 20 paylines, 3 reel-strip sets (BASE / POWER_BET / FREE_SPINS) of 5 strips × 32 symbols each, complete pay table, bonus buy options, Pick & Collect distribution, and round limits.
- Implemented stateless `math.engine.ReelEvaluator` (left-to-right matching, WILD substitution of STANDARD only, scatter breaks line runs, wild-prefix vs base-run max comparison, `bet * limits.maxWinPerRoundMultiplier` cap with `MAX_WIN_CAPPED` reason code) returning immutable `EvaluationResult` and `WinLine` records wire-aligned with A.7.

### Tests (Milestone 1)

- `SlotMathLoaderTest`: loads the `aztec-fire/v1` fixture and asserts structural sanity; rejects missing file, header mismatch, unknown top-level field, and bad structural invariants.
- `ReelEvaluatorTest`: 5-of-a-kind payout, wild substitution, scatter line break, partial 3-of-a-kind, leading-wild vs base-run max choice, max-win cap & reason code.
- Context-load test (`VelocityRgsApplicationTest`) exercises catalog loading via `@SpringBootTest`.

### Milestone 0 — Project Bootstrap & Cross-Cutting Foundation

- Initialized Maven project (`com.velocity:velocity-rgs:0.1.0-SNAPSHOT`) with Java 21 toolchain, Spring Boot 3.3.5, Lombok, MapStruct, springdoc-openapi, Micrometer Prometheus, and Testcontainers (Postgres + Redis).
- Established package skeleton per Appendix A.2 with `package-info.java` markers across all leaf packages.
- Wired Spring profiles `default`, `demo`, `wallet-internal`, `wallet-operator`, `test`, `simulator` via dedicated YAML files.
- Implemented `common/money` `Money` value object backed by `BigDecimal` with HALF_UP rounding, minor-unit conversion, and EUR/USD-only validation.
- Implemented `common/error`: `ErrorCode` enum with full A.8 mapping, `ApiError` record, `GlobalExceptionHandler` mapping `RgsException`, `OptimisticLockingFailureException`, validation failures, and uncaught exceptions to canonical responses.
- Implemented `common/idempotency`: `idempotency_record` Flyway migration `V1__idempotency_record.sql`, JPA entity, repository, Postgres write-through store with optional Redis acceleration, and `@Idempotent` aspect (header `Idempotency-Key`, SHA-256 payload hash, `Idempotent-Replay: true` header on hit, `IDEMPOTENCY_KEY_CONFLICT` on hash mismatch).
- Implemented `observability/MdcCorrelationFilter` (`X-Trace-Id` extract/generate, MDC `traceId` populate/clear).
- Implemented JWT auth filter (HS256, claims `sub`, `sid`, `cur`, `exp`, `roles`) and request-scoped `PlayerContext` bean.
- Configured Logback JSON encoder, Micrometer Prometheus registry, exposed only `health`, `info`, `prometheus` actuator endpoints.
- Wired springdoc-openapi and the `springdoc-openapi-maven-plugin` to optionally emit `docs/openapi.yaml` under the `openapi` Maven profile.

### Tests

- Full `@SpringBootTest` integration suite booting against shared Testcontainers Postgres + Redis via `TestcontainersInitializer`.
- `GlobalExceptionHandler` mapping table covered (parameterized over `ErrorCode`).
- Idempotency aspect: hit / miss / replay / conflict / missing-header scenarios.
- JWT filter: valid, missing, malformed, expired, bad-issuer tokens.
- `MdcCorrelationFilter`: trace-id generation and echo.
- `Money`: scale rejection, normalization, minor-units round-trip, arithmetic, HALF_UP rounding.
