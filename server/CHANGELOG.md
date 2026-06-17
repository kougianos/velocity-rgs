# Changelog

All notable changes to this project are documented here.

## [Unreleased]

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
