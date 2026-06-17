# AI Code Generation Blueprint: Scalable Remote Gaming Server (RGS) Core & Slot Mechanics Engine

## Role & Context
You are an expert iGaming Backend Architect and Principal Software Engineer specializing in highly secure, deterministic, and certified Remote Gaming Servers (RGS). 

Your objective is to build a robust, modular, and enterprise-grade Slot RGS Foundation using **Java 21**, **Spring Boot 3.x**, and **Lombok**. The server must function as a strict, deterministic state machine where the client acts solely as a visual renderer. All evaluation, math, state transitions, and random number generations must happen securely on the backend.

---

## Architectural Principles & Strict Constraints

1. **Pure Determinism:** The backend must never trust the client. A spin request accepts only configuration inputs (e.g., base bet, feature toggles like Power Bet). The engine calculates the random stops, constructs the grid matrix, evaluates wins, and updates state.
2. **Strict State Isolation:** A player’s session state determines what actions are legal. If a user has remaining Free Spins, the standard `SPIN` endpoint must be blocked, and only a `FREE_SPIN` action can advance the state machine.
3. **Thread Safety & Stateful Persistence:** Game services must be stateless at the application layer. Persistent and cache boundaries must prevent concurrency issues such as race-condition double-dipping.
4. **Math Model Separation (Data-Driven):** Reel strips, paytables, and feature weights must **never** be hardcoded into evaluation loops. They must be loaded dynamically via configuration classes (JSON blueprints) to allow easy RTP tuning without changing code.
5. **iGaming-Grade Randomness:** Use `java.security.SecureRandom` for all RNG components to emulate cryptographic security standards required by GLI-19 compliance.
6. **Feature Purchase Compliance:** Any paid feature entry (e.g., Bonus Buy) must be explicitly requested by the client, priced by server-side configuration, and fully auditable through immutable transaction and session-event records.
7. **Single Source of Financial Truth:** Bet debits, buy-in debits, and win credits must be written atomically with idempotency keys to prevent duplicate wallet mutations during retries/timeouts.
8. **Wallet API Contract Parity:** The RGS financial flow must follow operator-style wallet operations (`authenticate`, `balance`, `credit`, `debit`, `rollback`) even in POC mode.
9. **POC Boundary Preservation:** For this project scope, wallet must live in the same Spring Boot service under a dedicated `wallet` package, but RGS must interact through wallet endpoints/contracts (not direct repository mutation) to preserve future externalization.

---

## Technical Stack Requirements
* **Language:** Java 21 (Utilize modern features such as Record types for immutable DTOs, Pattern Matching for switch expressions, and Virtual Threads for scaling high-concurrency simulation loops).
* **Framework:** Spring Boot 3.x (Spring Web, Spring Data JPA).
* **Libraries:** Lombok (for boilerplate reduction), Jackson (for flexible JSON configurations), MapStruct (optional, or manual mapping for clean DTO separation).
* **Testing:** JUnit 5, AssertJ, Mockito, and Testcontainers (Postgres + Redis). IMPORTANT: Tests are mandatory in each iteration. Mocking policy: **mock only the `SecureRandomNumberGenerator` and outbound HTTP boundaries**; everything else (Postgres, Redis, wallet gateway in POC mode) must run against real Testcontainers in integration tests using `@SpringBootTest`. Slice tests (`@WebMvcTest`, `@DataJpaTest`) are allowed for narrow unit coverage only.

## Persistence Layer Strategy (Explicit)

Use a hybrid persistence model with clear separation of concerns between Postgres and Redis.

### Postgres (System of Record)
Use Postgres for durable, auditable, and relational data that must survive restarts and support reconciliation/reporting.

Required Postgres use cases:
* Game rounds and round lifecycle events (round start, spin result, feature entry, feature completion).
* Financial transactions and immutable wallet ledger entries (`debit`, `credit`, `rollback`).
* Bonus buy purchase events and regulatory reason codes.
* Dispute/replay artifacts metadata (RNG seeds references, action timeline indexes, result hashes).
* Idempotency records for monetary and state-mutating operations.
* Reconciliation data sets and operational audit queries.

### Redis (Low-Latency Session State)
Use Redis for fast-changing, short-lived runtime state that optimizes gameplay responsiveness.

Required Redis use cases:
* Active player game session state (FSM state, next action, temporary feature payload references).
* In-progress feature context (for example Pick and Collect opened positions and remaining picks) when not yet finalized.
* Idempotency response cache for quick replay of recent identical requests.
* Short TTL locks/guards for per-player action sequencing.

### Persistence Rules
* Postgres remains the source of truth for money, rounds, and audit trails.
* Redis loss must not cause financial inconsistency; sessions can be rebuilt from durable Postgres records when needed.
* Wallet balance mutations are committed in Postgres transaction boundaries; Redis may only mirror derived session-facing balance snapshots.
* On session resume, reconstruct canonical state from Postgres, then hydrate Redis cache/session entries.

### Concurrency and Session Consistency Rules (Authoritative)
* Every mutable session record must carry a monotonic `sessionVersion` used for optimistic concurrency control.
* Every game action must include expected `sessionVersion`; stale writes must fail with conflict error (`409`).
* Redis session entries must have TTL (default 30 minutes inactivity) and be refreshed on successful action.
* Conflict resolution rule: if Redis and Postgres differ, Postgres canonical snapshot wins and Redis is rehydrated.
* Per-player action lock in Redis must be short-lived (default 3 seconds) and used only as a contention guard, not as source of truth.

## Monetary and Currency Rules (Authoritative)

* Use `BigDecimal` for all monetary math.
* Enforce currency precision by ISO minor units (`EUR`/`USD` scale 2 unless configured otherwise).
* Rounding mode for payout and wallet operations: `HALF_UP`.
* Cross-currency operations are forbidden in a single session/round.
* Any amount received with invalid scale must be rejected by validation error before business logic.
* Persist both human-readable amount and minor-units integer (`amountMinor`) for audit stability.

## Idempotency Policy (Authoritative)

Apply to all state-mutating endpoints.

| Endpoint | Scope Key | Uniqueness Window | Replay Behavior |
|---|---|---|---|
| `/api/v1/slot/spin` | `playerId + idempotencyKey` | 24h | Return original response, no new state mutation |
| `/api/v1/slot/feature/buy` | `playerId + idempotencyKey` | 24h | Return original response, no extra debit |
| `/api/v1/slot/feature/pick` | `playerId + idempotencyKey` | 24h | Return original response, no extra pick resolution |
| `/api/v1/wallet/debit` | `playerId + transactionId + idempotencyKey` | 48h | Return original transaction outcome |
| `/api/v1/wallet/credit` | `playerId + transactionId + idempotencyKey` | 48h | Return original transaction outcome |
| `/api/v1/wallet/rollback` | `playerId + originalTransactionId + idempotencyKey` | 48h | Return original rollback outcome |

Additional rules:
* Idempotency records are durable in Postgres; Redis cache is optional acceleration only.
* Same key with different payload must fail (`409`) and include mismatch reason.
* Replay responses must include original `timestamp` and `transactionId`.

---

## Core Feature Requirements

### 0. Wallet Integration (Production Model + POC Implementation)
* Production model (target architecture): RGS communicates with an external Operator Wallet API using the operations `authenticate`, `balance`, `credit`, `debit`, `rollback`.
* POC model (current scope): implement wallet in the same server in a dedicated package namespace, while preserving the exact operator-style API contract and call sequence.
* RGS must call wallet through API-oriented interfaces (gateway/adapter abstraction), not by bypassing wallet business rules.
* POC default integration mode is in-process gateway invocation (no loopback HTTP requirement). Wallet REST endpoints still exist as contract mirror and test surface.
* Every monetary mutation must include:
  * `playerId`
  * `sessionId`
  * `roundId`
  * `transactionId`
  * `idempotencyKey`
  * `amount`
  * `currency`
  * `transactionType`

Wallet Endpoints (POC inside same server):
* `POST /api/v1/wallet/authenticate`
  * Validates player context/session token and returns wallet eligibility status for game actions.
* `GET /api/v1/wallet/balance`
  * Returns current player balance and currency.
* `POST /api/v1/wallet/debit`
  * Debits amount for bet placement and bonus buy purchases with idempotent processing.
* `POST /api/v1/wallet/credit`
  * Credits amount for base wins, free-spin settlement, and Pick & Collect finalization.
* `POST /api/v1/wallet/rollback`
  * Reverts a previously successful financial operation using original `transactionId` and `rollbackReason`.

POC Package and Design Rules:
* Use a clear package boundary such as `...wallet.api`, `...wallet.service`, `...wallet.domain`, `...wallet.persistence`.
* Implement an RGS-facing `WalletGateway` interface and keep RGS unaware of wallet storage details.
* Provide two gateway implementations strategy-ready:
  * `InternalWalletGateway` (active in POC profile)
  * `OperatorWalletGateway` (stub/interface-ready for future external API integration)
* Enforce idempotency for `debit`, `credit`, and `rollback` by unique key constraints and deterministic replay responses.
* Keep wallet ledger immutable; corrections must be done through compensating transactions (`rollback`), never by in-place updates.

### 0.1 API Contracts (Normative for Agent Implementation)

The following contracts are mandatory and must not be freely invented by implementation agents.

Common error contract:
```json
{
  "code": "INSUFFICIENT_FUNDS",
  "message": "Wallet debit failed",
  "httpStatus": 409,
  "traceId": "c8c90d1f-24df-4cd3-95e2-33d3015d5d31",
  "timestamp": "2026-06-17T10:15:30Z"
}
```

Wallet debit request:
```json
{
  "playerId": "p-1001",
  "sessionId": "s-2001",
  "roundId": "r-3001",
  "transactionId": "t-4001",
  "idempotencyKey": "idem-5001",
  "amount": 1.50,
  "currency": "EUR",
  "transactionType": "BET"
}
```

Wallet debit response:
```json
{
  "transactionId": "t-4001",
  "status": "SUCCESS",
  "balanceBefore": 100.00,
  "balanceAfter": 98.50,
  "currency": "EUR",
  "processedAt": "2026-06-17T10:15:30Z",
  "idempotentReplay": false
}
```

Wallet credit request/response follows the same shape with `transactionType` in `WIN`, `FEATURE_WIN`, `ADJUSTMENT`.

Wallet rollback request:
```json
{
  "playerId": "p-1001",
  "originalTransactionId": "t-4001",
  "transactionId": "t-rollback-7001",
  "idempotencyKey": "idem-rollback-7001",
  "rollbackReason": "DOWNSTREAM_FAILURE"
}
```

Wallet rollback response:
```json
{
  "transactionId": "t-rollback-7001",
  "originalTransactionId": "t-4001",
  "status": "SUCCESS",
  "processedAt": "2026-06-17T10:16:00Z",
  "idempotentReplay": false
}
```

Canonical enums (must be reused consistently):
* `WalletTransactionType`: `BET`, `BONUS_BUY`, `WIN`, `FEATURE_WIN`, `ROLLBACK`
* `WalletTransactionStatus`: `SUCCESS`, `REJECTED`
* `RollbackReason`: `DOWNSTREAM_FAILURE`, `TECHNICAL_ERROR`, `OPERATOR_CANCEL`
* `GameState`: `BASE_GAME`, `FREE_SPINS_AWAITING`, `FREE_SPINS_LOOP`, `PICK_COLLECT_AWAITING`, `PICK_COLLECT_LOOP`
* `GameCommand`: `SPIN`, `START_FREE_SPINS`, `BUY_FEATURE`, `START_PICK_COLLECT`, `PICK`

Mandatory wallet error codes:
* `AUTH_FAILED`
* `INSUFFICIENT_FUNDS`
* `DUPLICATE_TRANSACTION`
* `IDEMPOTENCY_KEY_CONFLICT`
* `ORIGINAL_TRANSACTION_NOT_FOUND`
* `CURRENCY_MISMATCH`
* `VALIDATION_ERROR`

Required RGS -> Wallet Call Sequence:
* Game init/resume:
  * `authenticate` -> `balance`
* Base spin:
  * `debit` (bet) -> spin evaluation -> `credit` (if win > 0)
* Bonus Buy:
  * `debit` (buy cost) -> feature entry
* Feature settlement (Free Spins / Pick & Collect):
  * `credit` (final accumulated feature payout)
* Failure handling:
  * call `rollback` for the relevant `transactionId` when a downstream step fails after a successful debit/credit.

### 1. Base Game Configuration ($3 \times 5$ Grid)
* A standard slot matrix consisting of 3 rows and 5 columns.
* A configurable payline evaluation module. **Default for this project: fixed 20-line configuration** (payline coordinates defined in math JSON, see Appendix A.4). A `WAYS_TO_WIN` mode is an interface-level extension point and is out of scope for Milestone 1-5.

### 2. Free Spins Feature
* Triggered by landing $\ge 3$ Scatter symbols anywhere on the grid during a base spin.
* Grants a configurable number of free rounds (e.g., 10 Free Spins).
* Free spin rounds must use a specialized, high-RTP set of reel strips (defined in the configuration).
* Support for re-triggers (landing additional scatters inside the free spin state adds to the spin counter).

### 3. Power Bet Feature
* An optional mechanic toggled via the request payload.
* Activating the Power Bet increases the base bet by a specific multiplier (e.g., $+50\%$).
* In return, the engine dynamically alters the simulation math model: it swaps the standard reel configurations for customized "Power Reels" that feature a higher frequency of Scatter symbols or boosted wild multipliers.

### 4. Bonus Buy Feature
* A configurable paid shortcut that allows a player to directly enter a feature state without waiting for a natural trigger.
* The backend must support at least two buy types via config:
  * `FREE_SPINS_BUY` (directly awards configured free spins)
  * `PICK_COLLECT_BUY` (directly enters Pick & Collect feature loop)
* Buy prices must be configurable as a bet multiplier (e.g., `80x`, `120x`) and calculated from the active base bet.
* Bonus Buy must be gated by explicit game policy flags:
  * `bonusBuyEnabled` global game flag
  * jurisdiction/profile allowlist (market-level compliance toggle)
  * minimum player balance check and responsible-gaming lock checks
* Bonus Buy must not bypass wallet consistency rules:
  * Validate affordability first
  * Deduct buy cost atomically
  * Enter target feature state in the same transaction
* RTP and contribution accounting must be separated by channel:
  * `BASE_GAME_RTP`
  * `BONUS_BUY_RTP`
  * `OVERALL_RTP`

Implementation Notes:
* Add `bonusBuyOptions` into math/config JSON with: `buyType`, `costMultiplier`, `targetState`, `initialFeaturePayload`.
* Add server-side `BonusBuyPolicyService` for eligibility checks (jurisdiction, feature toggles, session state legality).
* Extend session engine with command `BUY_FEATURE` and strict transition validation.
* Persist a `FeaturePurchaseEvent` record containing `playerId`, `sessionId`, `buyType`, `cost`, `currency`, `timestamp`, `idempotencyKey`, and resulting `state`.

### 5. Pick & Collect Feature
* A server-driven deterministic bonus round where players reveal hidden picks on a board and accumulate instant or progressive rewards.
* The client only sends a pick position index. The server resolves outcome and updates feature state.
* Pick board composition must be generated and frozen at feature start (no per-click re-roll):
  * Credits tiles
  * Collect tiles (bank current collected values)
  * Modifier tiles (multiplier/additive boosts)
  * End/Blank tiles (if supported by game math)
* Support configurable completion rules:
  * Fixed number of picks
  * End condition tiles
  * Target collect threshold
* Pick & Collect must support two entry paths:
  * Natural trigger during base/free-spin results
  * Direct entry via `PICK_COLLECT_BUY`
* All picks must be validated server-side:
  * Position must be in range and not already opened
  * Session must be in `PICK_COLLECT_LOOP`
  * Action must satisfy `nextActionAllowed`

Implementation Notes:
* Introduce immutable feature payload in session: `PickCollectState` with `boardSeed`, `hiddenTiles`, `openedPositions`, `currentCollected`, `totalFeatureWin`, `remainingPicks`, `status`.
* Use deterministic board generation based on secure RNG output captured at feature start; persist seed and resolved board snapshot for replay/audit.
* Add evaluation component `PickCollectEngine`:
  * `startFeature(config, rng)` -> initializes board/state
  * `applyPick(state, position)` -> validates pick, resolves tile, updates totals and completion status
  * `finalizeFeature(state)` -> returns final win package and transition signal to `BASE_GAME`
* Emit per-pick event logs for BI and dispute replay: before-state hash, action, resolved tile, after-state hash.

---

## Scope Note (Demo Mode First)

Default runtime mode for this project is **Demo Mode** using fake money for gameplay demonstration and feature validation.

Operating mode matrix (authoritative):

| Capability | Demo Mode (Default) | Wallet Integration Mode (Later Iteration) |
|---|---|---|
| Currency | Fake money only | Real wallet-backed balance |
| Wallet Gateway | `InternalWalletGateway` | `OperatorWalletGateway` |
| Failure Policy | Deterministic simulation failures | Real wallet error propagation + rollback |
| Compliance Logging | Basic technical logging | Full operational audit and reconciliation |

Production-grade wallet behavior is planned in **Milestone 4A** and **Milestone 5**.

## Implementation Roadmap & Milestone Breakdowns

Execute this implementation sequentially. Do not move to the next milestone until the current milestone's logic and its corresponding tests are fully complete and stable.

### Milestone 1: Mathematical Domain Models & JSON Engine Configurations
Define the immutable data objects and configuration structures that govern the slot's behavior.

* **Task 1.1:** Create domain objects or records for `Symbol` (ID, Name, Type: STANDARD, WILD, SCATTER), `Payline` (coordinate paths across the 5 columns), and `PayTable` (payout mappings for $3\times$, $4\times$, $5\times$ matches per symbol).
* **Task 1.2:** Implement a `SlotMathConfiguration` class. This must load from a JSON layout representing:
    * Base Game Reel Strips (Arrays of Symbol IDs per column).
    * Power Bet Reel Strips (Alternative arrays with boosted Scatter distribution).
    * Free Spins Reel Strips.
* **Task 1.3:** Build a `ReelEvaluator` component. Given a static 2D array matrix of symbols, it must cleanly parse paylines, match left-to-right sequences, account for Wild substitutions, and compute total credit payouts based on the active bet.

### Milestone 2: Cryptographic RNG Engine & Grid Generator
Build the core generation mechanics.

* **Task 2.1:** Implement a `SecureRandomNumberGenerator` component wrapping `java.security.SecureRandom`. It should accept an array length (the reel strip size) and return an integer index.
* **Task 2.2:** Implement a `GridGenerationEngine`. It must accept the selected game state's reel strips, fetch random stop positions from the RNG component, and build the final $3 \times 5$ symbol matrix using a wrapping index strategy (if a reel runs off the end of the array, wrap back around to index 0).

### Milestone 3: Finite State Machine (RGS Session Engine)
Implement the core workflow engine tracking user state transitions.

* **Task 3.1:** Create a `GameSession` entity tracking: `playerId`, current state (`BASE_GAME`, `FREE_SPINS_AWAITING`, `FREE_SPINS_LOOP`, `PICK_COLLECT_AWAITING`, `PICK_COLLECT_LOOP`), `currentBet`, `remainingFreeSpins`, `accumulatedFreeSpinsWin`, `activeFeaturePayload`, and `nextActionAllowed`.
* **Task 3.2:** Write a state transition engine using Java 21 pattern matching over an algebraic/sealed state command sequence. 
    * If a normal spin hits 3 scatters, state shifts from `BASE_GAME` to `FREE_SPINS_AWAITING`.
    * The next valid command must be a `START_FREE_SPINS` request or a free spin iteration which shifts state to `FREE_SPINS_LOOP`.
    * If a spin triggers Pick & Collect, state shifts from `BASE_GAME` or `FREE_SPINS_LOOP` to `PICK_COLLECT_AWAITING`, then to `PICK_COLLECT_LOOP` on start.
    * If a `BUY_FEATURE` command is valid, state shifts directly to the configured target awaiting/loop state based on buy type.
    * When `remainingFreeSpins == 0`, transition back to `BASE_GAME` and flush accumulated wins to the main wallet balance.
    * When Pick & Collect completion condition is met, transition back to `BASE_GAME` and flush feature win atomically.

### Milestone 4: Spring Boot API Controllers & Service Facade
Expose the state engine through optimized REST API endpoints.

* **Task 4.1:** Develop a `SlotEngineService` facade that encapsulates the database/session fetch, feeds parameters into the grid engine, processes payouts via the `ReelEvaluator`, modifies session state based on features, and persists changes atomically.
* **Task 4.2:** Construct `SlotGameController` with two distinct endpoints:
    * `POST /api/v1/slot/init` - Initializes or fetches the current state of a player session (critical for browser reloads/disconnections).
    * `POST /api/v1/slot/spin` - Accepts a `SpinRequest` containing `betSize` and `powerBetActive` flag. Returns an immutable `SpinResponse` payload.
* **Task 4.3:** Add feature command endpoints:
    * `POST /api/v1/slot/feature/buy` - Accepts `BonusBuyRequest` (`buyType`, `betSize`, `idempotencyKey`). Returns updated session state and feature bootstrap payload.
    * `POST /api/v1/slot/feature/pick` - Accepts `PickRequest` (`position`, `idempotencyKey`). Returns resolved tile result, updated Pick & Collect state, and interim/final win impact.
* **Task 4.4:** Add idempotency support on mutable endpoints (`/spin`, `/feature/buy`, `/feature/pick`) to safely handle retries without duplicate financial or state transitions.
* **Task 4.5:** Structure the `SpinResponse` JSON output to match frontend rendering timelines:
    ```json
    {
      "matrix": [[2,5,1,8,9],[3,12,1,1,4],[7,8,2,3,11]],
      "stopPositions": [14, 82, 4, 119, 43],
      "winLines": [
        { "lineId": 3, "symbolId": 1, "count": 4, "payout": 150.0 }
      ],
      "featuresTriggered": {
        "freeSpinsAwarded": 10,
        "isPowerBetActive": true,
        "pickCollectTriggered": false,
        "bonusBuyExecuted": false
      },
      "sessionState": {
        "currentState": "FREE_SPINS_AWAITING",
        "remainingSpins": 10,
        "totalAccumulatedWin": 150.0
      }
    }
    ```

* **Task 4.6:** Add dedicated response contracts:
    * `BonusBuyResponse` including `buyType`, `cost`, `enteredState`, `featureInitPayload`.
    * `PickResponse` including `position`, `resolvedTileType`, `resolvedValue`, `currentCollected`, `remainingPicks`, `featureCompleted`, `featureTotalWin`.

### Milestone 4A: Wallet API and Gateway Layer (POC-Ready, Production-Shaped)
* **Task 4A.1:** Implement wallet controller endpoints for `authenticate`, `balance`, `debit`, `credit`, `rollback` under `/api/v1/wallet`.
* **Task 4A.2:** Add `WalletGateway` abstraction and route all RGS financial operations through it.
* **Task 4A.3:** Implement internal wallet service and persistence in dedicated `wallet` package with immutable transaction ledger.
* **Task 4A.4:** Add idempotency middleware/policy for financial endpoints and include consistent replay response semantics.
* **Task 4A.5:** Add error model mapping for wallet failures (insufficient funds, duplicate transaction, original transaction not found, currency mismatch, authentication failure).

### Milestone 5: Wallet, Ledger, and Auditability Hardening
* **Task 5.1:** Implement atomic wallet operations with transaction boundaries around bet/buy debits and win credits.
* **Task 5.2:** Introduce immutable ledger tables/events for: spin debit, bonus buy debit, spin win credit, feature win credit, rollback reason codes.
* **Task 5.3:** Add replay support endpoint (internal/admin) to reconstruct a session from persisted RNG seeds, board snapshots, and action events for dispute handling.
* **Task 5.4:** Add reconciliation report job comparing RGS game rounds against wallet ledger entries to detect orphan credits/debits.
* **Task 5.5:** Add profile-based switch configuration so internal wallet can be replaced by external operator wallet gateway without changing RGS core logic.

---

## Testing & Validation Requirements

You must include clean, expressive integration and unit tests matching the following criteria:

1.  **Deterministic Engine Unit Tests:**
    * Mock out the `SecureRandomNumberGenerator` to return deterministic index values (e.g., `[0, 0, 0, 0, 0]`).
    * Assert that the grid generates the exact expected symbol layout.
    * Assert that the `ReelEvaluator` outputs the mathematically precise payout and flags active lines without regression.
2.  **State Machine Integration Tests:**
    * Simulate a multi-spin player sequence. 
    * Mock an RNG output that hits a 3-scatter trigger during a standard spin. Assert that the returned API payload flags the next state as `FREE_SPINS_AWAITING`.
    * Attempt to execute a standard base-spin command while in a Free Spin state, asserting that the system throws a `400 Bad Request` or an explicit state validation exception.
3.  **Power Bet Logic Integration Tests:**
    * Verify that executing a spin with `powerBetActive: true` calculates the correct financial overhead deduction from the player's balance.
    * Verify via mocking or inspection that alternative "Power Bet Reel Strips" were passed into the matrix generator instead of standard base strips.
4.  **Bonus Buy Integration Tests:**
    * Verify buy request fails with a validation error when bonus buy is disabled by market or game config.
    * Verify buy request fails when player state is not `BASE_GAME` (or other explicitly allowed states).
    * Verify successful buy debits exact configured amount (`bet * multiplier`) once even under duplicate idempotent retries.
    * Verify resulting state equals configured target feature state and includes expected bootstrap payload.
5.  **Pick & Collect Feature Tests:**
    * Verify board is generated once at feature start and remains immutable across picks.
    * Verify duplicate pick position returns validation error and does not mutate totals.
    * Verify collect/modifier tile interactions produce mathematically correct interim and final totals.
    * Verify completion transition credits wallet exactly once and resets feature payload.
6.  **Mini-RTP Simulator Validation Test (Bonus Architecture Verification):**
    * Write a specialized JUnit/Integration test class (disabled by default via `@Disabled` or setup as a separate profile) that runs an automated loop of `100,000` spins against the engine components.
    * Collect overall statistics: Total Bet, Total Win, count of Free Spin activations, count of Bonus Buy usage, Pick & Collect entry rate, and average feature payout.
    * Print out the empirical RTP percentage to the console logs to prove the math foundation behaves accurately across scale.
7.  **Wallet Integration and Idempotency Tests:**
    * Verify `authenticate` and `balance` are called before first monetary action in a new/resumed game session.
    * Verify successful base spin calls `debit` first and `credit` only when win amount is positive.
    * Verify duplicate `debit`/`credit` requests with same idempotency key do not create duplicate ledger effects.
    * Verify rollback correctly compensates the original transaction and cannot be executed twice for the same original transaction.
    * Verify insufficient funds on `debit` returns deterministic business error and does not alter game state.
    * Verify currency mismatch or invalid player authentication hard-fails before any state mutation.

---

## Product & UX-Driven Guardrails (Backend Enforced)

1. **Explicit Feature Availability Contract:** `init` response must include `availableActions` and `featureFlags` so clients can render or hide Bonus Buy and Pick actions safely.
2. **Latency Budget for Feature Actions:** `pick` action should target p95 under 120ms at normal load to preserve game flow rhythm.
3. **Resumability:** On reconnect, `init` must return current feature board state abstraction (opened positions and player-visible data) without exposing hidden unrevealed tile values.
4. **Regulatory Explainability:** Every paid/awarded feature entry must store structured reason codes (e.g., `TRIGGERED_BY_SCATTER`, `ENTERED_VIA_BUY`) and be queryable.
5. **Abuse Prevention:** Enforce per-session action sequencing and replay protection via idempotency key + action counter versioning.
6. **POC-to-Production Wallet Compatibility:** Internal wallet endpoints must mirror operator wallet request/response semantics so migration to external wallet is configuration and adapter change, not game-engine rewrite.

## Instructions for Code Generation
Generate the source files modularly. Focus on writing clean, readable code with explicit type declarations, leveraging Java 21 syntax updates wherever applicable. Ensure all exceptions are intercepted via a global controller advice handler to return standardized error formats back to the client application.

**Hard rule — Do Not Invent:** the following items are fixed by Appendix A and must NOT be renamed, restructured, or substituted by the implementation agent:
* Root package, module/package layout, dependency versions.
* Endpoint paths, HTTP methods, request/response field names, header names.
* Enum names and values.
* Error codes and their HTTP status mapping.
* Database table and column names, Flyway migration filenames.
* Redis key naming convention and TTLs.
* Spring profile names.
* Math JSON file layout and field names.

If a requirement seems missing, prefer the default declared in Appendix A. If Appendix A is silent, raise the gap explicitly in code review comments rather than inventing a contract.

---

## Appendix A: Normative Defaults (Authoritative)

This appendix resolves all otherwise-implicit decisions. Every value here is binding for the implementation agent.

### A.1 Project Bootstrap

* **Build tool:** Maven (single module).
* **Java toolchain:** Java 21 (`maven.compiler.release=21`).
* **Spring Boot:** `3.3.x` (latest patch at build time).
* **Root package:** `com.velocity.rgs`.
* **Artifact:** `groupId=com.velocity`, `artifactId=velocity-rgs`, `version=0.1.0-SNAPSHOT`.
* **Mandatory dependencies (pin in `pom.xml`):**
  * `spring-boot-starter-web`, `spring-boot-starter-validation`, `spring-boot-starter-actuator`
  * `spring-boot-starter-data-jpa`, `spring-boot-starter-data-redis`
  * `org.postgresql:postgresql`
  * `org.flywaydb:flyway-core`, `flyway-database-postgresql`
  * `org.projectlombok:lombok` (provided)
  * `com.fasterxml.jackson.module:jackson-module-parameter-names`
  * `org.mapstruct:mapstruct` + `mapstruct-processor` (DTO mapping is MapStruct, not manual)
  * `org.springdoc:springdoc-openapi-starter-webmvc-ui` (auto-generated OpenAPI at `/v3/api-docs` and `/swagger-ui.html`)
  * `io.micrometer:micrometer-registry-prometheus`
  * Test scope: `spring-boot-starter-test`, `org.testcontainers:postgresql`, `org.testcontainers:junit-jupiter`, `com.redis:testcontainers-redis`, `org.assertj:assertj-core`
* **Lombok scope rule:** allowed on JPA entities, services, components. Forbidden on Java records (use records' built-in semantics). DTOs are records with `@Builder` only when the constructor has 5+ fields.

### A.2 Module / Package Layout

```
com.velocity.rgs
├── config           # Spring configuration, Jackson, OpenAPI, security, virtual-thread executor
├── common
│   ├── error        # ApiError, GlobalExceptionHandler, ErrorCode enum
│   ├── idempotency  # IdempotencyKey, IdempotencyStore, @Idempotent aspect
│   └── money        # Money value object, currency rounding utils
├── math
│   ├── config       # SlotMathConfiguration, JSON loaders
│   ├── domain       # Symbol, Payline, PayTable, ReelStrip records
│   └── engine       # ReelEvaluator, GridGenerationEngine
├── rng              # SecureRandomNumberGenerator, RngDraw record (audit)
├── session
│   ├── domain       # GameSession entity, FSM state/command sealed types
│   ├── fsm          # SessionStateMachine, transition rules
│   ├── persistence  # GameSessionRepository (JPA), SessionCache (Redis)
│   └── service
├── game
│   ├── api          # SlotGameController, request/response DTOs
│   ├── service      # SlotEngineService facade
│   └── feature
│       ├── freespins
│       ├── bonusbuy
│       └── pickcollect  # PickCollectEngine, PickCollectState
├── wallet
│   ├── api          # WalletController, DTOs
│   ├── service      # InternalWalletService
│   ├── gateway      # WalletGateway, InternalWalletGateway, OperatorWalletGateway
│   ├── domain       # WalletTransaction, LedgerEntry entities
│   └── persistence
├── audit            # FeaturePurchaseEvent, ReplayService, ReconciliationJob
└── observability    # Logging filter, MDC propagation, metrics
```

### A.3 Configuration Profiles

| Profile | Purpose |
|---|---|
| `default` | Local dev, uses Testcontainers-managed Postgres/Redis via `spring-boot-docker-compose` |
| `demo` | Default runtime mode, `InternalWalletGateway` active, fake money |
| `wallet-internal` | Equivalent to `demo` but with full audit logging enabled |
| `wallet-operator` | `OperatorWalletGateway` active (future external integration) |
| `test` | Integration test profile, Testcontainers wired by base test class |
| `simulator` | Enables `@RtpSimulationTest` execution (disabled in `default`) |

Selection: `SPRING_PROFILES_ACTIVE=demo` is the default for `mvn spring-boot:run`.

### A.4 Slot Math JSON — Canonical Layout

**Location:** `src/main/resources/math/<gameId>/<mathVersion>.json` (e.g. `math/aztec-fire/v1.json`). Loaded at startup via `SlotMathConfiguration`. Hot-reload is NOT supported in scope.

**Skeleton (all fields required unless marked optional):**

```json
{
  "gameId": "aztec-fire",
  "mathVersion": "v1",
  "grid": { "rows": 3, "cols": 5 },
  "symbols": [
    { "id": 1, "name": "ACE",      "type": "STANDARD" },
    { "id": 9, "name": "WILD",     "type": "WILD",    "substitutes": "STANDARD" },
    { "id": 12,"name": "SCATTER",  "type": "SCATTER" }
  ],
  "paylines": [
    { "id": 1, "coords": [[1,0],[1,1],[1,2],[1,3],[1,4]] }
  ],
  "payTable": {
    "1":  { "3": 5,  "4": 20, "5": 100 },
    "9":  { "3": 25, "4": 100,"5": 500 }
  },
  "reelStrips": {
    "BASE":      [[1,2,3,9,1,...], [...], [...], [...], [...]],
    "POWER_BET": [[1,12,3,9,12,...], [...], [...], [...], [...]],
    "FREE_SPINS":[[1,12,9,9,12,...], [...], [...], [...], [...]]
  },
  "scatterTriggers": { "minCount": 3, "freeSpinsAwarded": 10, "retriggerAwards": 5 },
  "freeSpins": { "betLockedToTriggerBet": true, "powerBetPersists": false, "maxRetriggerStack": 50 },
  "powerBet": { "betMultiplier": 1.50 },
  "bonusBuyOptions": [
    { "buyType": "FREE_SPINS_BUY",  "costMultiplier": 80,  "targetState": "FREE_SPINS_AWAITING",
      "initialFeaturePayload": { "freeSpinsAwarded": 10 } },
    { "buyType": "PICK_COLLECT_BUY","costMultiplier": 120, "targetState": "PICK_COLLECT_AWAITING",
      "initialFeaturePayload": { "boardSize": 12, "maxPicks": 5 } }
  ],
  "pickCollect": {
    "boardSize": 12,
    "completion": { "type": "FIXED_PICKS", "value": 5 },
    "tileDistribution": [
      { "type": "CREDITS",   "weight": 50, "valueRange": [1, 50] },
      { "type": "MULTIPLIER","weight": 20, "valueRange": [2, 5] },
      { "type": "COLLECT",   "weight": 20 },
      { "type": "BLANK",     "weight": 10 }
    ],
    "maxFeatureWinMultiplier": 5000
  },
  "limits": { "maxWinPerRoundMultiplier": 10000 }
}
```

* `coords` are `[row, col]` zero-indexed. Row 0 = top.
* `payTable` keys are `symbolId` (string due to JSON), inner keys are match counts.
* `WILD` substitutes the `substitutes` type only (never SCATTER).
* `Money` rounding applies to **final** payouts only; intermediate math stays in `BigDecimal`.

### A.5 Game Catalog

A minimal catalog is fixed. Multi-game scope is reserved but only one is shipped:

| gameId | mathVersion | currency | status |
|---|---|---|---|
| `aztec-fire` | `v1` | EUR/USD | ACTIVE |

Every request/response in `/api/v1/slot/*` MUST carry `gameId` and the server MUST stamp `mathVersion` on the response.

### A.6 Authentication & Headers

* **Slot + Wallet APIs** are protected by a single header: `Authorization: Bearer <jwt>`.
* JWT validation: HS256 with shared secret `rgs.security.jwt-secret` (env `RGS_JWT_SECRET`). Issuer = `velocity-rgs`. Required claims: `sub` (= `playerId`), `sid` (= sessionId), `cur` (currency), `exp`.
* **Idempotency** is delivered via header `Idempotency-Key: <uuid>` on every mutating endpoint (NOT in body). The body fields previously shown as `idempotencyKey` are removed from the body schema; the header is the single source.
* **Correlation:** clients MAY send `X-Trace-Id`; server generates one if absent. Echoed in all responses and errors.

### A.7 Complete API Contracts (Slot)

**`POST /api/v1/slot/init`**

Request:
```json
{ "gameId": "aztec-fire", "currency": "EUR" }
```

Response (`200`):
```json
{
  "sessionId": "s-2001",
  "sessionVersion": 7,
  "gameId": "aztec-fire",
  "mathVersion": "v1",
  "currency": "EUR",
  "balance": 98.50,
  "currentState": "FREE_SPINS_AWAITING",
  "remainingFreeSpins": 10,
  "accumulatedFreeSpinsWin": 0.00,
  "currentBet": 1.00,
  "availableActions": ["START_FREE_SPINS"],
  "featureFlags": { "bonusBuyEnabled": true, "powerBetEnabled": true },
  "activeFeatureView": null
}
```

`activeFeatureView` exposes player-visible state only (e.g. for Pick & Collect: opened positions and revealed values, never hidden tiles).

**`POST /api/v1/slot/spin`**

Request:
```json
{
  "gameId": "aztec-fire",
  "sessionId": "s-2001",
  "sessionVersion": 7,
  "betSize": 1.00,
  "powerBetActive": false
}
```

Response: as in Task 4.5, extended with required fields:
```json
{
  "sessionId": "s-2001",
  "sessionVersion": 8,
  "roundId": "r-3001",
  "mathVersion": "v1",
  "betDebited": 1.00,
  "totalWin": 150.0,
  "matrix": [[2,5,1,8,9],[3,12,1,1,4],[7,8,2,3,11]],
  "stopPositions": [14, 82, 4, 119, 43],
  "winLines": [{ "lineId": 3, "symbolId": 1, "count": 4, "payout": 150.0 }],
  "featuresTriggered": {
    "freeSpinsAwarded": 10, "isPowerBetActive": false,
    "pickCollectTriggered": false, "bonusBuyExecuted": false,
    "reasonCodes": ["TRIGGERED_BY_SCATTER"]
  },
  "sessionState": {
    "currentState": "FREE_SPINS_AWAITING",
    "remainingSpins": 10,
    "totalAccumulatedWin": 150.0
  },
  "availableActions": ["START_FREE_SPINS"]
}
```

**`POST /api/v1/slot/feature/buy`**

Request:
```json
{
  "gameId": "aztec-fire",
  "sessionId": "s-2001",
  "sessionVersion": 8,
  "buyType": "FREE_SPINS_BUY",
  "betSize": 1.00
}
```

Response:
```json
{
  "sessionId": "s-2001",
  "sessionVersion": 9,
  "buyType": "FREE_SPINS_BUY",
  "cost": 80.00,
  "currency": "EUR",
  "enteredState": "FREE_SPINS_AWAITING",
  "featureInitPayload": { "freeSpinsAwarded": 10 },
  "availableActions": ["START_FREE_SPINS"]
}
```

**`POST /api/v1/slot/feature/pick`**

Request:
```json
{
  "gameId": "aztec-fire",
  "sessionId": "s-2001",
  "sessionVersion": 12,
  "position": 4
}
```

Response:
```json
{
  "sessionId": "s-2001",
  "sessionVersion": 13,
  "position": 4,
  "resolvedTileType": "MULTIPLIER",
  "resolvedValue": 3,
  "currentCollected": 45.00,
  "remainingPicks": 2,
  "featureCompleted": false,
  "featureTotalWin": null,
  "availableActions": ["PICK"]
}
```

### A.8 Error Model & Status Mapping

`ApiError` shape (already defined) is reused unchanged. Added: `details` optional array for field validation.

```json
{
  "code": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "httpStatus": 400,
  "traceId": "c8c90d1f-...",
  "timestamp": "2026-06-17T10:15:30Z",
  "details": [{ "field": "betSize", "reason": "must be > 0" }]
}
```

Mapping table (authoritative):

| Error Code | HTTP | Thrown When |
|---|---|---|
| `VALIDATION_ERROR` | 400 | Bean validation / scale mismatch / unknown enum |
| `AUTH_FAILED` | 401 | Missing/invalid JWT |
| `FORBIDDEN_ACTION` | 403 | JWT player ≠ request player |
| `SESSION_NOT_FOUND` | 404 | Session id unknown |
| `ILLEGAL_STATE_TRANSITION` | 409 | Command not allowed for current FSM state |
| `SESSION_VERSION_CONFLICT` | 409 | Stale `sessionVersion` |
| `IDEMPOTENCY_KEY_CONFLICT` | 409 | Same key, different payload |
| `DUPLICATE_TRANSACTION` | 409 | Wallet detects duplicate `transactionId` |
| `INSUFFICIENT_FUNDS` | 409 | Wallet rejects debit |
| `ORIGINAL_TRANSACTION_NOT_FOUND` | 404 | Rollback references unknown tx |
| `CURRENCY_MISMATCH` | 409 | Cross-currency operation |
| `BONUS_BUY_DISABLED` | 409 | Policy gate (jurisdiction/flag) |
| `MAX_WIN_REACHED` | 409 | Round/feature win exceeds cap |
| `INTERNAL_ERROR` | 500 | Uncaught / unmapped exception |

`GlobalExceptionHandler` maps domain exceptions → `ErrorCode` one-to-one. Logging level: `WARN` for 4xx (except 401/403 = `INFO`), `ERROR` for 5xx.

### A.9 Persistence — Postgres Schema (Flyway)

* Migrations under `src/main/resources/db/migration/`, naming `V<seq>__<snake_case>.sql`, starting `V1__init.sql`.
* Conventions: snake_case tables/columns; surrogate `id BIGSERIAL` PK; mandatory `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`; `version BIGINT NOT NULL DEFAULT 0` for entities that need optimistic locking; monetary columns as `NUMERIC(19,4)`; minor-units mirror as `BIGINT`.

Required tables (minimum):

| Table | Purpose | Key columns |
|---|---|---|
| `game_session` | Canonical session snapshot | `id, player_id, game_id, math_version, current_state, current_bet, remaining_free_spins, accumulated_free_spins_win, active_feature_payload JSONB, next_action_allowed, session_version, currency, created_at, updated_at` |
| `game_round` | One row per spin | `id, session_id, player_id, round_id UNIQUE, math_version, bet_amount, bet_amount_minor, total_win, total_win_minor, matrix JSONB, stop_positions JSONB, rng_draws JSONB, reason_codes JSONB, created_at` |
| `feature_purchase_event` | Bonus Buy audit | `id, player_id, session_id, buy_type, cost, cost_minor, currency, idempotency_key, resulting_state, created_at` |
| `pick_collect_snapshot` | Replay artifact | `id, session_id, round_id, board_seed, board JSONB, opened_positions JSONB, final_win, created_at` |
| `wallet_transaction` | Immutable ledger | `id, player_id, transaction_id UNIQUE, original_transaction_id, type, status, amount, amount_minor, currency, balance_before, balance_after, idempotency_key, rollback_reason, created_at` |
| `wallet_balance` | Per player current balance | `player_id PK, currency, balance, balance_minor, version, updated_at` |
| `idempotency_record` | Replay store | `scope, key, payload_hash, response_body JSONB, status_code, created_at, expires_at, PRIMARY KEY(scope, key)` |

Indexes: at minimum `(player_id, created_at)` on rounds, transactions, and purchase events.

### A.10 Redis — Keys, TTLs, Locks

| Key pattern | Purpose | TTL | Format |
|---|---|---|---|
| `rgs:session:{playerId}` | Hydrated session JSON | 30 min, refreshed on each action | JSON via Jackson |
| `rgs:idem:{scope}:{key}` | Idempotency replay cache | mirror Postgres TTL (24h or 48h) | JSON |
| `rgs:lock:player:{playerId}` | Per-player action lock | 3s | `SET key val NX PX 3000`, value = caller UUID for safe release via Lua |

Implementation: plain Spring Data Redis (`StringRedisTemplate`); Redisson is NOT used. Locks acquired in `SlotEngineService` around any state mutation; released in `finally` only if value matches.

### A.11 RNG Audit Strategy (Resolved)

`SecureRandom` is non-reproducible by design. Audit/replay uses **draw capture**, not seed replay:

* `SecureRandomNumberGenerator` returns `RngDraw(int boundExclusive, int value, long sequence)`.
* Every draw within a round is appended to an in-memory list and persisted as `game_round.rng_draws` JSONB at round commit.
* `ReplayService` reconstructs the round by feeding the recorded draws back into a `DeterministicReplayRng` that pops values in sequence.
* This satisfies dispute/reconstruction without claiming reproducible seeding.

### A.12 Concurrency Wiring

* Session entity uses JPA `@Version` for the `session_version` column → automatic optimistic locking; controller catches `OptimisticLockingFailureException` → `SESSION_VERSION_CONFLICT`.
* Request thread executor: virtual threads enabled via `spring.threads.virtual.enabled=true`. `@Async` is NOT used for game flow; only the RTP simulator uses an `ExecutorService` of virtual threads.

### A.13 Wallet Edge Cases

* **Max win cap:** enforced as `bet * limits.maxWinPerRoundMultiplier`; excess is truncated and reason code `MAX_WIN_CAPPED` recorded.
* **In-flight feature on session timeout:** Redis session expiry does NOT cancel an active feature; on next `init`, server rehydrates from Postgres and the player resumes. Features cannot be force-closed by timeout.
* **Bet during free spins:** `betLockedToTriggerBet=true` (per math JSON); server rejects mismatching `betSize` in free spin loop with `VALIDATION_ERROR`.
* **Power Bet during free spins:** governed by `freeSpins.powerBetPersists`; default false.
* **Allowed currencies:** EUR, USD (scale 2). Any other currency in JWT `cur` → `CURRENCY_MISMATCH`.

### A.14 Observability

* **Logging:** Logback JSON encoder (`logstash-logback-encoder`). Required MDC fields on every log line in request scope: `traceId`, `playerId`, `sessionId`, `roundId` (when present), `gameId`.
* **Servlet filter** `MdcCorrelationFilter` extracts/generates `X-Trace-Id` and populates MDC; cleared in `finally`.
* **Metrics (Micrometer + Prometheus):**
  * `rgs.spin.count` (tags: `gameId`, `powerBet`, `result=win|loss`)
  * `rgs.spin.duration` (timer)
  * `rgs.feature.entered` (tags: `featureType`, `source=natural|buy`)
  * `rgs.wallet.operation` (tags: `op`, `status`)
  * `rgs.idempotency.replay` counter
* **Actuator:** `/actuator/health`, `/actuator/prometheus`, `/actuator/info` exposed; rest disabled.

### A.15 OpenAPI

`springdoc-openapi` auto-generates the spec. Implementation MUST:
* Annotate every controller and DTO so generated schema matches Appendix A.7 exactly.
* Commit the generated spec snapshot to `docs/openapi.yaml` via a Maven goal (`springdoc-openapi-maven-plugin`) bound to `verify`.
* CI must fail if the committed `openapi.yaml` drifts from the freshly generated one.

### A.16 Reconciliation & Replay (Milestones 5.3 / 5.4)

* **Replay endpoint:** `POST /api/v1/admin/replay/{roundId}` — protected by separate JWT scope claim `roles=["ADMIN"]`. Returns full reconstructed matrix, win lines, RNG draws, and resulting state.
* **Reconciliation job:** scheduled at `0 5 * * * *` (every hour, minute 5) via `@Scheduled`. Compares `game_round` win/bet totals against `wallet_transaction` credits/debits per player per hour bucket. Discrepancies written to `audit_reconciliation_finding` table and logged at `ERROR` with metric `rgs.reconciliation.discrepancy`.

### A.17 Definition of Done (per milestone)

A milestone is "done" only when:
1. `mvn -B verify` passes (compile + tests + checkstyle + spotbugs if configured).
2. Test coverage on changed packages ≥ 80% lines (JaCoCo).
3. New endpoints appear in generated `openapi.yaml` and the file is committed.
4. Flyway migrations are forward-only (no edits to applied versions).
5. No new `TODO` markers left in committed code.
6. README is NOT auto-updated (per repo instructions); CHANGELOG entry under `## [Unreleased]` is mandatory.

### A.18 Glossary

* **RGS** — Remote Gaming Server.
* **RTP** — Return To Player, expected payout ratio over infinite spins.
* **GLI-19** — Gaming Labs International standard for online gaming systems.
* **Scatter** — Symbol whose count anywhere on the grid triggers features regardless of payline.
* **Wild** — Substitute symbol for STANDARD symbols (never SCATTER in this game).
* **Ways-to-Win** — Win evaluation mode counting any left-to-right symbol adjacency; OUT OF SCOPE.
* **FSM** — Finite State Machine.
* **DRBG** — Deterministic Random Bit Generator (not used; see A.11).