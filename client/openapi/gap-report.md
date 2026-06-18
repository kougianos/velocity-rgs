# Client OpenAPI Gap Report

This file tracks deviations between the client's expected API surface and the
authoritative server contract. Per `client-requirements.md` Section 7.1 every
client-side workaround for a missing or evolving backend endpoint MUST be
recorded here.

## 2026-06-18 — Hand-curated `openapi.yaml` snapshot

**Need.** The client needs typed wrappers for every backend endpoint at M1 in
order to ship the API layer.

**Current contract.** The server only emits `server/docs/openapi.yaml` when the
`openapi` Maven profile boots the application (springdoc-openapi-maven-plugin).
That profile depends on a running Postgres + Redis from Testcontainers, so the
generated YAML is not currently a committed artifact under
`server/docs/openapi.yaml`.

**Proposed change.** Enable a CI job in the server that emits the YAML and
commits it as part of `mvn -B verify`. When the file lands the client will
overwrite `client/openapi/openapi.yaml` from it verbatim and re-run
`pnpm api:gen`.

**Workaround until backend ships.** `client/openapi/openapi.yaml` is hand-curated
from the Java record definitions under `server/src/main/java/com/velocity/rgs`
(verified against `SlotGameController`, `WalletController`,
`AdminQaController`, `ReplayAdminController`, `SimulatorAdminController`,
`DevTokenController` and their DTOs). Field names, types, nullability, and
enum values mirror the source one-to-one. The `RoundReplayResult` envelope is
loose (`additionalProperties: true`) because the typed shape is not yet
required by any client surface; it is consumed only by the admin replay tab
(M8) and the gap will be closed before that milestone.

## Known pre-existing gaps (carried from `client-requirements.md` §7.1)

| Gap | Workaround |
|---|---|
| No `/api/v1/game/math-summary` endpoint. The `BonusBuyPanel` needs `bonusBuyOptions` and the spin animator needs `paylines`. | Static client-side mirror at `src/game/math/aztec-fire.ts` (introduced in M4/M5). Server's `feature/buy` response is authoritative for the actual `cost`. |
| No paylines metadata endpoint. | Use the same `src/game/math/aztec-fire.ts` mirror. If client mirror `mathVersion` ≠ server's, log `WARN` and skip the win-line overlay. |
| No `availableBets` endpoint. | Hardcoded demo bet ladder in `src/wallet/components/BetSelector.tsx` (default `[0.20, 0.50, 1.00, 2.00, 5.00, 10.00]`, overridable via `VITE_BET_LADDER`). |
