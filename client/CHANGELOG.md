# Changelog

All notable changes to the Velocity RGS Slot Client are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.1.0] - 2026-06-18

First milestone-complete cut of the Velocity RGS Slot Client (M0 → M9).

### Added

- **M0 — Bootstrap & Tooling.** Vite + React 18 + TypeScript strict, Vitest,
  Playwright, MSW, ESLint with `no-restricted-paths` boundaries, Husky/
  lint-staged, `pnpm verify` aggregate script.
- **M1 — API Layer.** `openapi-typescript` generation, typed axios client
  with `X-Trace-Id`, typed `RgsHttpError` / `RgsNetworkError`, wrappers for
  every `slot/`, `wallet/`, `admin/`, and `dev/` endpoint, `Money` value
  object, idempotency key helper.
- **M2 — Auth & Session FSM Mirror.** `authStore`, dev-token panel, JWT
  decode without signature check, `sessionStore` (read-only mirror),
  `useSessionInit`, `useSessionRecovery`, route guards.
- **M3 — Wallet.** `walletStore`, balance refetch interval, balance pill,
  bet selector, error UX for `INSUFFICIENT_FUNDS` / `CURRENCY_MISMATCH`.
- **M4 — Pixi Stage.** Pixi v8 app lifecycle, asset loader, `Reel`,
  `SlotGrid`, `SlotStage` rendering a server-supplied matrix.
- **M5 — Base Game Spin.** `useSpin` mutation, `SpinButton`,
  `SpinAnimator` (deterministic, no `Math.random`), reason-code banners,
  latency feedback.
- **M6 — Free Spins & Power Bet.** `FreeSpinsOverlay`,
  `useStartFeature`, retrigger burst, settlement tween, `PowerBetToggle`.
- **M7 — Bonus Buy & Pick & Collect.** `BonusBuyPanel`, `useBuyFeature`,
  `PickBoard` / `PickBoardScene`, `useFeaturePick`, resume from
  `activeFeatureView`.
- **M8 — Admin Tooling.** `/admin` route with Wallet, Session, Round,
  Replay, and Simulator tabs; `client/http/velocity-rgs-client.http`.
- **M9 — Observability, A11y, Performance.**
  - Structured `logger` with `traceId` / `playerId` / `sessionId` /
    `roundId` / `gameId` enrichment and optional `sendBeacon` sink to
    `VITE_LOG_SINK_URL`.
  - `web-vitals` integration (LCP / INP / CLS) routed through the logger.
  - Collapsible `DebugHud` gated by `VITE_ENABLE_DEBUG_HUD` surfacing the
    last `traceId`s emitted by error toasts.
  - `SpinAnnouncer` polite live-region for screen-readers (Pixi canvas is
    `aria-hidden="true"`).
  - Lazy-loaded `AdminPage` chunk so admin code stays out of the
    player-facing initial bundle.
  - Vite Pixi manual chunk and `target: "es2022"` build profile.

### Removed

- `/debug` route and `DebugPage` (M1 smoke surface).

### Notes

- README is intentionally not auto-updated per repo convention.
