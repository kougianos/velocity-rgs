import { http, HttpResponse } from 'msw';

/**
 * Canonical wire fixtures mirror Appendix A of client-requirements.md.
 * They serve as the smoke seed for MSW until M1 expands the catalogue.
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
} as const;

const walletBalanceResponse = {
  playerId: 'p-1001',
  balance: 98.5,
  currency: 'EUR',
} as const;

export const handlers = [
  http.post('*/api/v1/slot/init', () => HttpResponse.json(initResponse)),
  http.get('*/api/v1/wallet/balance', () => HttpResponse.json(walletBalanceResponse)),
];
