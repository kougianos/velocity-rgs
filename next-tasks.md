# Velocity RGS — Next Tasks

Derived from reading `src/` at commit `aced129`, not from generic iGaming market advice.

**Scope:** demo-money only. No real money, no operator integration, no production release, no
target jurisdiction. The goal is a **solid demo-money platform that is genuinely mature** —
broad mechanics, real features, nothing half-built.

That scope is doing a lot of work below. It kills most of what a standard iGaming checklist
would tell you to build (see §5), and it promotes the things that were previously blocked on
having an operator. **In demo mode you are the operator** — so jackpots, tournaments and
promos stop being blocked and start being the point.

---

## What "mature" means here

Not compliance. Not scale. For a demo-money RGS, maturity is two things:

1. **Breadth of what the engine can express.** A slot platform is judged on mechanic
   coverage. This is the biggest gap in the repo and §1 is entirely about it.
2. **Depth of player-facing features.** Jackpots, tournaments, promos, shareable wins — the
   things that make it read as a product rather than a math server with a test harness.

### The finding that reorders everything

**You have one slot mechanic wearing three skins.**

`aztec-fire`, `frost-crown` and `inferno-riches` differ only in grid size (4/5/6 reels),
paytable, reel strips, volatility tuning, and theme. All three share an identical symbol
taxonomy (A/K/Q/J + themed) and all three run the same
`ReelEvaluator.evaluate(int[][] matrix, bet, math) → EvaluationResult` — a single-shot payline
scan. A grep across `slot/` and `games/` returns **zero** hits for cascade, tumble, avalanche,
ways-to-win, cluster, Megaways, hold-and-spin, respin, and expanding/sticky/walking wilds.

What *is* built is good and worth keeping in view: wilds, scatters, multipliers, free spins
with retrigger, bonus buy with a policy service, pick & collect, power bet, deterministic
replay, and an RTP simulator.

But three games on one evaluator is a **catalog**, not a **platform**. Adding a fourth
payline game with a new theme adds nothing architecturally. Adding a second *evaluation model*
doubles what the engine can express. That is where the maturity is.

---

## §0 — RTP regression in CI — **DONE**

**This gated all of §1.** Every mechanic in §1 changes RTP math substantially; cascades in
particular make effective RTP non-hand-calculable. Adding them without a regression net means
shipping broken games and not finding out.

My original write-up of this section was **wrong in three ways**, corrected here after reading
the tests rather than inferring from the pom:

- I claimed the convergence assertions needed building. They already existed — `RtpSimulationVerificationTest` asserts every slot converges within 0.6pp of its declared target, and `BonusBuyRtpVerificationTest` guards the bonus-buy channel (the one a real historical bug lived in).
- I claimed roulette and blackjack had "no equivalent net today." **False** — `RouletteRtpVerificationTest` and `BlackjackRtpSimulationTest` both exist and are tagged `slow`.
- I asked for hit frequency and max-win distribution in the artifact. `RtpReport` exposes neither, so that is engine work, not CI wiring. Deliberately not done — see the follow-up below.

The real gap was narrow: **these guards were good and simply never ran.**

- [x] Nightly CI job (`.github/workflows/rtp.yml`) running `mvn -Prtp test`. Also runs on PR/push touching `games/**`, the engines, or `rng/` — drift is caused by those files, so catching it at review beats finding out the next morning. Free: public repo, unlimited standard-runner minutes. No Postgres/Redis service needed — every guard is pure math with hand-wired collaborators.
- [x] **Split `slow` into guards vs design aids.** `GameRtpCalibrationHarness`, `BonusBuyCalibrationHarness` and `PickCollectFeatureMeasurementTest` are explicitly "not an assertion" — they print constants for a human to feed back into the game JSON. Running them in CI burns ~3M spins/game on tests that *cannot fail*. They are now `@Tag("calibration")`; `-Prtp` excludes them, new `-Pcalibrate` profile runs them.
- [x] **Raised `BASE_SPINS` 2M → 8M.** Measured (6 runs/game) rather than guessed: per-run σ was ~0.24/0.22/0.28pp against a 0.6pp tolerance — only ~1.8σ headroom, ≈10% chance of a spurious failure per run. A guard that cries wolf every ~10 days gets ignored. 4× spins halves σ, giving >3.5σ and ≈0.03% flake. Confirmed after the change: deviations fell to 0.185/0.121/0.117pp. Cost 82s → 272s (sublinear — JIT warmup amortises); full guard suite ~9 min locally.
- [x] Surefire reports uploaded as an artifact (30-day retention) and measured RTP echoed to the run summary.

**Acceptance met:** the guards fail on drift, and the tolerance/spin-count relationship is now
documented in the test so the next person does not silently re-introduce flake.

### Follow-ups this surfaced

- **`frost-crown` runs slightly hot (~+0.15pp) — accepted, won't fix.** Across 6 runs at 2M spins its mean landed at 96.201% vs a declared 96.00% (~2.3 standard errors — suggestive, not conclusive at n=6); a later 8M run came in at 96.121%, consistent with either a small positive bias or plain noise. It passes the 0.6pp tolerance comfortably either way, and the deviation is in the **player-favourable** direction: the game pays marginally more than declared, which costs the house rather than shortchanging anyone. In demo money that is a rounding error. Revisit only if `frost-crown`'s paytable is reshaped for another reason — `mvn -Pcalibrate test -Dtest=GameRtpCalibrationHarness` measures the scale directly.
- [ ] Optional: extend `RtpReport` with hit frequency and max-win distribution, then assert them. Note `aztec-fire/v1.json` *declares* `"Hit Frequency": "27.40%"` in its presentation block — a player-visible number that nothing currently verifies.

---

## §1 — Engine breadth (highest value)

Ordered by ratio of perceived-maturity to effort.

### 1.1 Ways-to-win evaluator (243 / 1024 ways) — **DONE**
The cheapest possible win: **same matrix, same reel strips, same RNG, different evaluator.**

**Done (commit 2 — the game):** **Jade Tiger**, a 3x5 / 243-ways game (`winModel: WAYS`), calibrated to
96% and covered by the RTP guard.

- [x] Hand-authored strips (symbols spread evenly rather than clumped — under ways, an accidental stack inflates per-reel hit counts and skews volatility). Wilds on reels 1–3 only; reels 0 and 4 are wild-free, mirroring `aztec-fire`, which **already had no wilds on reel 0** — the convention we enforced in commit 1 turned out to be the existing house style.
- [x] Calibrated in **one pass**: the harness measured `L=153.45%, P=4.00% → s=0.5996`, and P (Pick & Collect) is independent of the pay table while free-spin wins scale linearly with it, so the scale lands directly. Guard result: **96.0240%, deviation 0.0240pp** — the tightest of the four.
- [x] Added to `RtpSimulationVerificationTest` — ways now has statistical cover, not just unit tests. Guard runtime 9:04 → 13:28; workflow timeout raised 30 → 45 min.
- [x] Client: ways wins highlight the cells on the matched reels holding the symbol or a substituting wild (wild found by name, the convention `FILLER_SYMBOL_IDS` already used), and the win chip reads `Tiger ×4 · 27 ways` instead of the "Line null" it would have shown. New `jade` theme + hue token.

**A number worth keeping:** the real ways scale is **~7.3x** the payline coefficients, not the 12.15x (243/20) that the bet-split arithmetic suggests — because the average win hits ~1.7 ways. Not derivable on paper; the harness measured it.

**Done (commit 1 — engine seam):**

- [x] `WinEvaluator` interface (`model()` + `evaluate()`); `PaylineWinEvaluator` carries the original logic **verbatim**, `WaysWinEvaluator` is new. `ReelEvaluator` became the dispatcher and kept its name and no-arg constructor, so all four call sites (`SlotEngineService`, `ReplayService`, 3× `RtpSimulationService`) and every existing test compile untouched.
- [x] `winModel` in the game JSON, defaulting to `PAYLINES` when absent — which is every game authored so far. Config validation rejects the incoherent combinations: `PAYLINES` with no paylines, `WAYS` with paylines.
- [x] 11 unit tests covering ways counting, wild substitution, scatter breaks, multi-symbol wins, the cap, and both config rejections.
- [x] **Verified no drift on the shipped games**: `mvn -Prtp test` green after the refactor (base deviations 0.128/0.029/0.054pp). This is exactly what §0 was built for — it turned "the refactor looks equivalent" into evidence.

**Decisions worth knowing:**

- **Ways = product of per-reel counts over the *matched* reels**, not enumeration of whole grid paths. Enumerating 5-reel paths counts a 3-reel run once per continuation, inflating a 2-way win to 18 — that is "243 paylines", not ways-to-win. Covered by `reelsBeyondTheRunDoNotMultiplyIt`.
- **How wilds work under `WAYS`.** A wild-rich screen paying several symbols at once is *not* a defect — it is the point of a ways game. The single ambiguous case is a path made **entirely** of wilds: a wild belongs to every symbol's run simultaneously, so "wild substitutes for everything" and "wild pays as itself" cannot both hold without deciding what that path is worth. Resolving it exactly needs per-way inclusion-exclusion. Two config rules sidestep it instead, both enforced at load and both `WAYS`-only:
  1. **No wilds on reel 0.** Runs are anchored on the leftmost reel, so this makes an all-wild run *structurally impossible* rather than merely disallowed, and bounds simultaneous wins to what reel 0 shows (≤ rows). It is also the common convention in real ways games.
  2. **No wild pay table entries.** Rule 1 already makes a wild run unreachable, so such entries would be dead config — rejected rather than silently ignored.

  `PAYLINES` keeps both wilds on reel 0 and their own pay table, which is what all three shipped games do; a line pays once, for the better of its two runs, so there is no overlap to resolve.

  *Correction to an earlier draft of this file: I described wild-own-pay as causing "unbounded inflation calibration cannot absorb." That was overstated. The overlap needs a wild on every reel of the run at once, which is rare at normal wild density, and costs that one path up to 9x. Real, worth designing out — not catastrophic.*

**Follow-ups this left open:**

- [ ] **Jade Tiger has no bonus buy** (`bonusBuyOptions: []`), unlike the three payline games. Adding one needs its own calibration pass via `BonusBuyCalibrationHarness` plus a slot in `BonusBuyRtpVerificationTest`. Deliberately deferred to keep the ways work reviewable — the game is complete without it.
- [ ] Optional: expose `winModel` on the catalog so the lobby can badge "243 Ways". Today the client infers ways from a null `lineId` and the spec sheet carries the text, which works but means the lobby can't distinguish the models.
- [x] ~~Feed the scale into `.rgsgen_assemble.py`~~ — that generator was never committed and does not exist. `GameRtpCalibrationHarness`'s javadoc no longer points at it; game JSONs are hand-authored.

### 1.2 Cascading / tumbling reels
**The dominant modern slot mechanic** (Sweet Bonanza, Gates of Olympus). Its absence is the
most conspicuous thing about the catalog, and it is the architecturally significant one.

The engine is single-shot today: generate grid → evaluate once → return `EvaluationResult`.
Cascades need winning symbols removed, the grid refilled from the strips, and re-evaluation in
a loop until no wins — with a step multiplier that typically climbs per cascade.

This touches more than the evaluator:

- [ ] `EvaluationResult` becomes multi-step (`List<CascadeStep>`, each with its own grid, wins, and multiplier). Today it is a flat `(totalWin, winLines, reasonCodes)` record.
- [ ] `GridGenerationEngine` gains refill generation — **refills must draw through the same `RngDrawSink`**, or deterministic replay breaks. This is the correctness-critical part.
- [ ] `game_round.matrix` / `stop_positions` JSONB must persist the full cascade sequence, not a single grid. Verify `audit/replay/` reconstructs a cascade round bit-exact.
- [ ] Per-cascade progressive multiplier in game config.
- [ ] Client animates steps sequentially (`slot.js` renders one grid today).

**Acceptance:** a cascade round replays bit-exact from persisted draws. That test is the whole
point of the replay infrastructure — this is what it was built for.

**Do not start before §0 lands.** Cascade RTP is not hand-calculable; you need the simulator.

### 1.3 Hold & Spin / respin bonus
The other dominant modern mechanic (Lightning Link style), and it is **the natural carrier for
jackpots** (§2) — collect enough coin symbols, win a tier. Build after §2 so they land
together, or design the two in one pass.

- [ ] Respin state in the FSM — `SessionStateMachine` already models free spins, so the shape exists.
- [ ] Sticky symbol accumulation persisted across respins (`active_feature_payload` JSONB already exists on `GameSession`).
- [ ] Reset-on-win respin counter; jackpot award on full-grid.

### 1.4 Symbol-behaviour mechanics
Cheap once §1.1's evaluator seam exists. Batch them.

- [ ] Expanding wilds, sticky wilds, walking wilds (config-driven per game).
- [ ] Symbol upgrade / collection during free spins.
- [ ] Win-both-ways (trivial once the evaluator is pluggable).

---

## §2 — Jackpots

**Previously blocked on an operator. In demo mode, unblocked — and it is the single most
recognizable "mature casino platform" feature.** Cross-game progressives (Mini/Minor/Major/
Mega) shared across all three slots.

Demo money makes this genuinely safe to build: the exactly-once award problem is real, but
getting it wrong costs a reset, not a chargeback. That is exactly the kind of hard feature
worth building while mistakes are free.

- [ ] `jackpot_pool` table (Postgres as source of record; Redis as a read cache for the live ticker only — **not** the pool of record).
- [ ] Contribution atomic with the bet debit, inside the existing `@Transactional` boundary in `SlotEngineService.spin()`.
- [ ] Seed values, contribution rate, and award rules per tier in config; per-game contribution overrides.
- [ ] Exactly-once award — the `Idempotency-Key` infrastructure already gives you the mechanism; use it.
- [ ] `jackpot_win` audit rows + reconciliation coverage (`ReconciliationJob` will otherwise flag jackpot credits as unexplained).
- [ ] Live pool ticker in the lobby — this is most of the visual payoff.

**Community jackpot** (Mega hit shares a secondary pool with everyone spinning) is a genuine
differentiator and demo mode is the ideal place to prototype it. Ship the four tiers first.

---

## §3 — Player-facing depth

### 3.1 Share-a-win replay links
**Best leverage-to-effort in the repo.** `audit/replay/` already reconstructs any round
bit-exact from persisted draws, and `history.html` exists. This is a signed-URL and
presentation job, not engine work — you have already paid for the hard part.

- [ ] Signed, expiring public replay URL for a single round.
- [ ] Public replay view rendering the round from persisted draws (cascades make this much more fun to watch — sequence with §1.2).
- [ ] Scope the token to one round so it cannot enumerate other players' history.

### 3.2 Tournaments and leaderboards
Big, visible, demoable, and no longer blocked. This is also the one feature that would justify
a push transport — **for leaderboard updates, not for spins.**

- [ ] Tournament definition, opt-in, point accrual off the existing round stream.
- [ ] Leaderboard in Redis sorted sets, settled to Postgres.
- [ ] **SSE, not WebSockets.** A leaderboard is one-way; SSE gets you push without a STOMP dependency or touching the spin path.
- [ ] Prize pools awarded in demo currency.

### 3.3 Bonus / promo engine
Nothing like this exists. It is the substrate for free-spin grants, welcome offers, and
(eventually) the micro-bonusing idea — which needs the RG seam (§4.2) before it is defensible
even in demo, since "award free spins to a losing player" is the exact pattern to be careful
with.

- [ ] Grantable free-spin awards independent of an in-game trigger (`remaining_free_spins` already exists on `GameSession` — the state is there, the grant path is not).
- [ ] Bonus definitions, eligibility, expiry.
- [ ] Wagering-requirement tracking (a genuine maturity signal, and demo money makes it safe to get wrong).

---

## §4 — The joints

Quoted from the earlier review: *"there's no tenant column, no RG seam, and a wallet that
structurally can't hold two currencies. The polish is running ahead of the joints."*

Two of those three still hold under demo-only scope. **One does not, and I am dropping it —
see 4.3.**

### 4.1 The wallet is structurally single-currency per player
`V2__wallet_balance.sql` declares `PRIMARY KEY (player_id)` with `currency` as a plain column,
and `InternalWalletService.authenticate` throws `CURRENCY_MISMATCH` if the JWT currency differs
from the stored row. **A player can hold exactly one currency, permanently.**

Under demo scope this stops being a compliance concern and becomes **a feature**: a demo that
switches between EUR/USD/GBP wallets is a better demo, and demo mode auto-seeds balances
(`WalletDemoSeeder`) so multi-currency costs nothing to populate. It also happens to be a
`PRIMARY KEY` change — free at zero rows, a data migration later.

- [ ] Migration to `PRIMARY KEY (player_id, currency)`.
- [ ] Audit every `balanceRepository.findById(playerId)` call site — each silently assumes one row and **the compiler will not catch it**. This is the actual work.
- [ ] Seed multiple currency wallets per demo player; currency switcher in the lobby.
- [ ] Keep `CURRENCY_MISMATCH` for round-level mismatch (bet currency ≠ session currency) — that check is correct.

### 4.2 Cut the RG seam, and build RG as a product feature
There is no point in the spin path where a player-level policy check could go.

Reframed for demo scope: **RG here is not compliance, it is a maturity signal.** A demo that
shows session timers, reality checks, loss limits and cool-off reads as a serious platform.
And with no jurisdiction, you are free to build a sensible generic ruleset rather than guessing
at MGA vs UKGC specifics — the seam is jurisdiction-neutral, so a real ruleset drops in later
without touching the money path.

**The pattern already exists in your own code.** `buyFeature()` is `@Transactional` and calls
`BonusBuyPolicyService.validate(session, balance, betSize, jurisdiction)` inside the boundary,
with `jurisdiction` threaded from the controller. RG needs that exact shape on `spin()` /
`deal()` / `action()`.

- [ ] `rg/` package with a policy interface invoked inside the existing `@Transactional` boundary of each entry point, mirroring `BonusBuyPolicyService`.
- [ ] `RG_LIMIT_EXCEEDED` / `RG_SELF_EXCLUDED` in `common/error/ErrorCode`; FSM withholds `availableActions`.
- [ ] Demo-able ruleset: session duration limit, loss limit, wager limit, reality-check interval, cool-off.
- [ ] Self-exclusion needs token severing — a **Redis `jti` denylist** is enough here. Full asymmetric signing / JWKS is operator-integration work and stays out (§5).
- [ ] Player-facing RG panel in the lobby. Half the value of this feature is that it is visible.

### 4.3 Dropped: the `operator_id` / tenant column
I flagged this earlier and **I am withdrawing it under the new scope.** It was justified by
"you'll onboard a second operator eventually" — and with operator integration explicitly out,
that is speculative generality: a column with exactly one value, forever, that every query has
to carry.

The multi-currency PK (4.1) is *not* the same class, despite my having grouped them before —
it is exercised by a real demo feature on day one. The tenant column would not be exercised at
all.

Revisit if an operator ever becomes real. Until then it is dead weight.

---

## §5 — Explicitly out of scope

Not backlog. **Deliberately not building**, so nobody re-raises them:

| Item | Why not |
|---|---|
| Operator wallet resilience — retry, circuit breaker, pending-intent, timeout sweeper | `OperatorWalletGateway` is dead code under `wallet.mode=internal`. Operator integration is out. |
| Fail-closed `rgs.mode` default | We are deliberately staying in demo. The default is now correct. |
| Asymmetric JWT signing, JWKS, key rotation | Single-party demo. HS256 is fine. (Redis `jti` denylist for self-exclusion is in — see 4.2.) |
| RNG certification evidence, entropy/reseed policy | No jurisdiction. Draws are already persisted, which is the hard part. §0 covers the correctness need. |
| `game_round` partitioning / archival | No volume. Do not design for unobserved load. |
| Reconciliation distributed lock (ShedLock) | Single instance. Revisit only if replicas appear. |
| Rate limiting | No real traffic, no abuse surface. Add only if the demo gets hosted publicly. |
| Real-money payments, crypto/fiat, AML/KYC | Out by definition. |
| `be-requirements.md` restoration | **Deleted on purpose.** The README's dead links to it, `RUNNING_LOCALLY.md` and `CHANGELOG.md` are now removed (§6). |

---

## §6 — Hygiene

- [x] Remove the dead `README.md` links (`be-requirements.md`, `RUNNING_LOCALLY.md`, `CHANGELOG.md`) — all three deleted, README still advertised them. **Done.**
- [x] Fix README inconsistencies: `GET /api/v1/wallet/*` was wrong (only `/balance` is a GET; authenticate/debit/credit/rollback are POST), same for `/api/v1/admin/*` (set-balance and simulator/run are POST). `mvn -B verify` was described as running "all tests" while `test.excludedGroups` defaults to `slow`. **Done.**
- [ ] Optional: generate `docs/openapi.yaml` via the existing `-Popenapi` profile (`skip.openapi.gen` defaults `true`). Nothing references it today — springdoc already serves live docs at `/swagger-ui.html` in demo mode, so this is only worth doing if a checked-in spec is wanted.

---

## Suggested order

1. ~~**§0 — RTP regression in CI.**~~ **Done.** Gated everything else, and paid for itself immediately: it is what proved the §1.1 refactor left the shipped games untouched, and it measured the ways scale that arithmetic got wrong.
2. ~~**§1.1 — ways-to-win evaluator.**~~ **Done.** The engine now expresses two win models and the catalog is four slots across two mechanics rather than three skins on one. The `WinEvaluator` seam is what §1.4 builds on.
3. **§2 — jackpots.** ← **next.** Biggest visible payoff, and demo money makes the hard part safe to get wrong.
4. **§1.2 — cascades.** The big one. Do it after §2 so the replay/persistence rework happens once, with jackpot rounds already in the schema.
5. **§3.1 — share-a-win links.** Cheap, and far more compelling once cascades exist to watch.
6. **§4.1 + §4.2 — multi-currency, RG seam.** Both are visible demo features, not just joints.
7. **§1.3 — hold & spin**, carrying jackpot tiers from §2.
8. **§3.2 — tournaments**, then **§3.3 — promo engine**.
9. **§6** whenever.

**The one thing to keep checking:** every item above should either broaden what the engine can
express or show up on screen. This codebase's existing failure mode is polish running ahead of
product — a fourth payline reskin or a fifth audit report would be exactly that.
