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

* **Task 3.1:** Create a `GameSession` entity tracking: `playerId`, current state (`BASE_GAME`, `FREE_SPINS_AWAITING`, `FREE_SPINS_LOOP`), `currentBet`, `remainingFreeSpins`, `accumulatedFreeSpinsWin`, and `nextActionAllowed`.
* **Task 3.2:** Write a state transition engine using Java 21 pattern matching over an algebraic/sealed state command sequence. 
    * If a normal spin hits 3 scatters, state shifts from `BASE_GAME` to `FREE_SPINS_AWAITING`.
    * The next valid command must be a `START_FREE_SPINS` request or a free spin iteration which shifts state to `FREE_SPINS_LOOP`.
    * When `remainingFreeSpins == 0`, transition back to `BASE_GAME` and flush accumulated wins to the main wallet balance.

### Milestone 4: Spring Boot API Controllers & Service Facade
Expose the state engine through optimized REST API endpoints.

* **Task 4.1:** Develop a `SlotEngineService` facade that encapsulates the database/session fetch, feeds parameters into the grid engine, processes payouts via the `ReelEvaluator`, modifies session state based on features, and persists changes atomically.
* **Task 4.2:** Construct `SlotGameController` with two distinct endpoints:
    * `POST /api/v1/slot/init` - Initializes or fetches the current state of a player session (critical for browser reloads/disconnections).
    * `POST /api/v1/slot/spin` - Accepts a `SpinRequest` containing `betSize` and `powerBetActive` flag. Returns an immutable `SpinResponse` payload.
* **Task 4.3:** Structure the `SpinResponse` JSON output to match frontend rendering timelines:
    ```json
    {
      "matrix": [[2,5,1,8,9],[3,12,1,1,4],[7,8,2,3,11]],
      "stopPositions": [14, 82, 4, 119, 43],
      "winLines": [
        { "lineId": 3, "symbolId": 1, "count": 4, "payout": 150.0 }
      ],
      "featuresTriggered": {
        "freeSpinsAwarded": 10,
        "isPowerBetActive": true
      },
      "sessionState": {
        "currentState": "FREE_SPINS_AWAITING",
        "remainingSpins": 10,
        "totalAccumulatedWin": 150.0
      }
    }
    ```

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
4.  **Mini-RTP Simulator Validation Test (Bonus Architecture Verification):**
    * Write a specialized JUnit/Integration test class (disabled by default via `@Disabled` or setup as a separate profile) that runs an automated loop of `100,000` spins against the engine components.
    * Collect overall statistics: Total Bet, Total Win, and count of Free Spin activations. 
    * Print out the empirical RTP percentage to the console logs to prove the math foundation behaves accurately across scale.

## Instructions for Code Generation
Generate the source files modularly. Focus on writing clean, readable code with explicit type declarations, leveraging Java 21 syntax updates wherever applicable. Ensure all exceptions are intercepted via a global controller advice handler to return standardized error formats back to the client application.