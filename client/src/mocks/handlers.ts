import { HttpResponse, http } from 'msw';

/**
 * Canonical wire fixtures mirror Appendix A of `client-requirements.md`.
 * Each handler returns a deterministic happy-path response. Error paths and
 * dynamic scenarios are layered on per-test in tests/unit/**.
 */

const initResponse = {
  sessionId: 's-2001',
  sessionVersion: 7,
  gameId: 'aztec-fire',
  mathVersion: 'v1',
  currency: 'EUR',
  balance: 98.5,
  currentState: 'FREE_SPINS_AWAITING',
  remainingFreeSpins: 10,
  accumulatedFreeSpinsWin: 0.0,
  currentBet: 1.0,
  availableActions: ['START_FREE_SPINS'],
  featureFlags: { bonusBuyEnabled: true, powerBetEnabled: true },
  activeFeatureView: null,
};

const spinResponse = {
  sessionId: 's-2001',
  sessionVersion: 8,
  roundId: 'r-3001',
  mathVersion: 'v1',
  betDebited: 1.0,
  totalWin: 150.0,
  matrix: [
    [2, 5, 1, 8, 9],
    [3, 12, 1, 1, 4],
    [7, 8, 2, 3, 11],
  ],
  stopPositions: [14, 82, 4, 119, 43],
  winLines: [{ lineId: 3, symbolId: 1, count: 4, payout: 150.0 }],
  featuresTriggered: {
    freeSpinsAwarded: 10,
    isPowerBetActive: false,
    pickCollectTriggered: false,
    bonusBuyExecuted: false,
    reasonCodes: ['TRIGGERED_BY_SCATTER'],
  },
  sessionState: {
    currentState: 'FREE_SPINS_AWAITING',
    remainingFreeSpins: 10,
    accumulatedFreeSpinsWin: 150.0,
  },
  availableActions: ['START_FREE_SPINS'],
};

const featureStartResponse = {
  sessionId: 's-2001',
  sessionVersion: 9,
  currentState: 'FREE_SPINS_LOOP',
  remainingFreeSpins: 10,
  activeFeatureView: null,
  availableActions: ['SPIN'],
};

const featureBuyResponse = {
  sessionId: 's-2001',
  sessionVersion: 9,
  buyType: 'FREE_SPINS_BUY',
  cost: 80.0,
  currency: 'EUR',
  enteredState: 'FREE_SPINS_AWAITING',
  featureInitPayload: { freeSpinsAwarded: 10 },
  availableActions: ['START_FREE_SPINS'],
};

const featurePickResponse = {
  sessionId: 's-2001',
  sessionVersion: 13,
  position: 4,
  resolvedTileType: 'MULTIPLIER',
  resolvedValue: 3,
  currentCollected: 45.0,
  remainingPicks: 2,
  featureCompleted: false,
  featureTotalWin: null,
  availableActions: ['PICK'],
};

const walletBalanceResponse = {
  playerId: 'p-1001',
  balance: 98.5,
  currency: 'EUR',
};

const walletDebitResponse = {
  transactionId: 't-4001',
  status: 'SUCCESS',
  balanceBefore: 100.0,
  balanceAfter: 98.5,
  currency: 'EUR',
  processedAt: '2026-06-17T10:15:30Z',
  idempotentReplay: false,
};

const walletCreditResponse = {
  transactionId: 't-4002',
  status: 'SUCCESS',
  balanceBefore: 98.5,
  balanceAfter: 248.5,
  currency: 'EUR',
  processedAt: '2026-06-17T10:15:31Z',
  idempotentReplay: false,
};

const walletRollbackResponse = {
  transactionId: 't-5001',
  originalTransactionId: 't-4001',
  status: 'SUCCESS',
  processedAt: '2026-06-17T10:15:32Z',
  idempotentReplay: false,
};

const walletAuthenticateResponse = {
  playerId: 'p-1001',
  currency: 'EUR',
  balance: 98.5,
  eligible: true,
};

const devTokenResponse = {
  token: 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.demo.signature',
  expiresAt: '2026-06-17T11:15:30Z',
};

const setBalanceResponse = {
  playerId: 'p-1001',
  currency: 'EUR',
  balance: 500.0,
  version: 3,
  updatedAt: '2026-06-17T10:15:30Z',
};

const sessionInspectionResponse = {
  sessionId: 's-2001',
  playerId: 'p-1001',
  gameId: 'aztec-fire',
  mathVersion: 'v1',
  currency: 'EUR',
  currentState: 'BASE_GAME',
  currentBet: 1.0,
  remainingFreeSpins: 0,
  accumulatedFreeSpinsWin: 0.0,
  nextActionAllowed: 'SPIN',
  sessionVersion: 7,
  createdAt: '2026-06-17T10:00:00Z',
  updatedAt: '2026-06-17T10:15:30Z',
  cachedInRedis: true,
  activeFeaturePayload: null,
};

const roundInspectionMatrix = [
  [2, 5, 1, 8, 9],
  [3, 12, 1, 1, 4],
  [7, 8, 2, 3, 11],
];

const roundInspectionResponse = {
  roundId: 'r-3001',
  sessionId: 's-2001',
  playerId: 'p-1001',
  gameId: 'aztec-fire',
  mathVersion: 'v1',
  stateContext: 'BASE_GAME',
  betAmount: 1.0,
  totalWin: 150.0,
  currency: 'EUR',
  powerBetActive: false,
  betTransactionId: 't-4001',
  winTransactionId: 't-4002',
  createdAt: '2026-06-17T10:15:30Z',
  matrix: roundInspectionMatrix,
  stopPositions: [14, 82, 4, 119, 43],
  rngDraws: [0.1284, 0.7541, 0.0421, 0.9234, 0.4123],
  winLines: [{ lineId: 3, symbolId: 1, count: 4, payout: 150.0 }],
  reasonCodes: ['TRIGGERED_BY_SCATTER'],
};

const replayResponse = {
  roundId: 'r-3001',
  matches: true,
  matrix: roundInspectionMatrix,
  stopPositions: [14, 82, 4, 119, 43],
  totalWin: 150.0,
};

const simulatorRunResponse = {
  runId: 'sim-1',
  gameId: 'aztec-fire',
  mathVersion: 'v1',
  bet: 1.0,
  channels: {
    BASE: { spins: 800, totalBet: 800.0, totalWin: 760.0, rtpPercent: 95.0 },
    FREE_SPINS_BUY: { spins: 100, totalBet: 8000.0, totalWin: 7600.0, rtpPercent: 95.0 },
    PICK_COLLECT_BUY: { spins: 100, totalBet: 12000.0, totalWin: 11400.0, rtpPercent: 95.0 },
  },
  overall: { spins: 1000, totalBet: 20800.0, totalWin: 19760.0, rtpPercent: 95.0 },
  elapsedMillis: 42,
  generatedAt: '2026-06-17T10:15:30Z',
  freeSpinTriggers: 35,
  pickEntries: 12,
};

export const handlers = [
  http.post('*/api/v1/slot/init', () => HttpResponse.json(initResponse)),
  http.post('*/api/v1/slot/spin', () => HttpResponse.json(spinResponse)),
  http.post('*/api/v1/slot/feature/start', () => HttpResponse.json(featureStartResponse)),
  http.post('*/api/v1/slot/feature/buy', () => HttpResponse.json(featureBuyResponse)),
  http.post('*/api/v1/slot/feature/pick', () => HttpResponse.json(featurePickResponse)),
  http.post('*/api/v1/wallet/authenticate', () => HttpResponse.json(walletAuthenticateResponse)),
  http.get('*/api/v1/wallet/balance', () => HttpResponse.json(walletBalanceResponse)),
  http.post('*/api/v1/wallet/debit', () => HttpResponse.json(walletDebitResponse)),
  http.post('*/api/v1/wallet/credit', () => HttpResponse.json(walletCreditResponse)),
  http.post('*/api/v1/wallet/rollback', () => HttpResponse.json(walletRollbackResponse)),
  http.post('*/api/v1/dev/token', () => HttpResponse.json(devTokenResponse)),
  http.post('*/api/v1/admin/wallet/balance', () => HttpResponse.json(setBalanceResponse)),
  http.get('*/api/v1/admin/session/:playerId', () =>
    HttpResponse.json(sessionInspectionResponse),
  ),
  http.get('*/api/v1/admin/round/:roundId', () => HttpResponse.json(roundInspectionResponse)),
  http.post('*/api/v1/admin/replay/:roundId', () => HttpResponse.json(replayResponse)),
  http.post('*/api/v1/admin/simulator/run', () => HttpResponse.json(simulatorRunResponse)),
];
