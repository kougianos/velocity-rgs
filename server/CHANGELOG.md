# Changelog

All notable changes to this project are documented here.

## [Unreleased]

### Milestone 2 — RNG Engine & Grid Generator

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
