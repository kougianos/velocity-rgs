# AI Code Generation Blueprint: Scalable Remote Gaming Server (RGS) Core & Slot Mechanics Engine

## Role & Context
You are an expert iGaming Backend Architect and Principal Software Engineer specializing in highly secure, deterministic, and certified Remote Gaming Servers (RGS). 

Your objective is to build a robust, modular, and enterprise-grade Slot RGS Foundation using **Java 21**, **Spring Boot 3.x**, and **Lombok**. The server must function as a strict, deterministic state machine where the client acts solely as a visual renderer. All evaluation, math, state transitions, and random number generations must happen securely on the backend.

---

## Architectural Principles & Strict Constraints

1. **Pure Determinism:** The backend must never trust the client. A spin request accepts only configuration inputs (e.g., base bet, feature toggles like Power Bet). The engine calculates the random stops, constructs the grid matrix, evaluates wins, and updates state.
2. **Strict State Isolation:** A player’s session state determines what actions are legal. If a user has remaining Free Spins, the standard `SPIN` endpoint must be blocked, and only a `FREE_SPIN` action can advance the state machine.
3. **Thread Safety & Statestate Persistence:** Game sessions must be stateless at the application layer, using an absolute transactional database boundary (simulated via JPA/Hibernate or Redis) to avoid concurrency issues like race-condition double-dipping.
4. **Math Model Separation (Data-Driven):** Reel strips, paytables, and feature weights must **never** be hardcoded into evaluation loops. They must be loaded dynamically via configuration classes (JSON blueprints) to allow easy RTP tuning without changing code.
5. **iGaming-Grade Randomness:** Use `java.security.SecureRandom` for all RNG components to emulate cryptographic security standards required by GLI-19 compliance.
6. **Feature Purchase Compliance:** Any paid feature entry (e.g., Bonus Buy) must be explicitly requested by the client, priced by server-side configuration, and fully auditable through immutable transaction and session-event records.
7. **Single Source of Financial Truth:** Bet debits, buy-in debits, and win credits must be written atomically with idempotency keys to prevent duplicate wallet mutations during retries/timeouts.

---

## Technical Stack Requirements
* **Language:** Java 21 (Utilize modern features such as Record types for immutable DTOs, Pattern Matching for switch expressions, and Virtual Threads for scaling high-concurrency simulation loops).
* **Framework:** Spring Boot 3.x (Spring Web, Spring Data JPA).
* **Libraries:** Lombok (for boilerplate reduction), Jackson (for flexible JSON configurations), MapStruct (optional, or manual mapping for clean DTO separation).
* **Testing:** JUnit 5, AssertJ, and Mockito.

---

## Core Feature Requirements

### 1. Base Game Configuration ($3 \times 5$ Grid)
* A standard slot matrix consisting of 3 rows and 5 columns.
* A configurable payline evaluation module (standard 20-line configuration or mathematical Ways-to-Win calculation).

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

### Milestone 5: Wallet, Ledger, and Auditability Hardening
* **Task 5.1:** Implement atomic wallet operations with transaction boundaries around bet/buy debits and win credits.
* **Task 5.2:** Introduce immutable ledger tables/events for: spin debit, bonus buy debit, spin win credit, feature win credit, rollback reason codes.
* **Task 5.3:** Add replay support endpoint (internal/admin) to reconstruct a session from persisted RNG seeds, board snapshots, and action events for dispute handling.

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

---

## Product & UX-Driven Guardrails (Backend Enforced)

1. **Explicit Feature Availability Contract:** `init` response must include `availableActions` and `featureFlags` so clients can render or hide Bonus Buy and Pick actions safely.
2. **Latency Budget for Feature Actions:** `pick` action should target p95 under 120ms at normal load to preserve game flow rhythm.
3. **Resumability:** On reconnect, `init` must return current feature board state abstraction (opened positions and player-visible data) without exposing hidden unrevealed tile values.
4. **Regulatory Explainability:** Every paid/awarded feature entry must store structured reason codes (e.g., `TRIGGERED_BY_SCATTER`, `ENTERED_VIA_BUY`) and be queryable.
5. **Abuse Prevention:** Enforce per-session action sequencing and replay protection via idempotency key + action counter versioning.

## Instructions for Code Generation
Generate the source files modularly. Focus on writing clean, readable code with explicit type declarations, leveraging Java 21 syntax updates wherever applicable. Ensure all exceptions are intercepted via a global controller advice handler to return standardized error formats back to the client application.