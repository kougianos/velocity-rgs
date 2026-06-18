import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { FeatureStartResponse } from '@/api/slot/featureStart';
import type { SlotInitResponse } from '@/api/slot/init';
import type { SpinResponse } from '@/api/slot/spin';

import { useSessionStore } from './sessionStore';

const canonicalInit: SlotInitResponse = {
  sessionId: 's-2001',
  sessionVersion: 7,
  gameId: 'aztec-fire',
  mathVersion: 'v1',
  currency: 'EUR',
  balance: 98.5,
  currentState: 'FREE_SPINS_AWAITING',
  remainingFreeSpins: 10,
  accumulatedFreeSpinsWin: 0,
  currentBet: 1,
  availableActions: ['START_FREE_SPINS'],
  featureFlags: { bonusBuyEnabled: true, powerBetEnabled: true },
  activeFeatureView: null,
};

const canonicalSpin: SpinResponse = {
  sessionId: 's-2001',
  sessionVersion: 8,
  roundId: 'r-3001',
  mathVersion: 'v1',
  betDebited: 1,
  totalWin: 150,
  matrix: [
    [2, 5, 1, 8, 9],
    [3, 12, 1, 1, 4],
    [7, 8, 2, 3, 11],
  ],
  stopPositions: [14, 82, 4, 119, 43],
  winLines: [{ lineId: 3, symbolId: 1, count: 4, payout: 150 }],
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
    accumulatedFreeSpinsWin: 150,
  },
  availableActions: ['START_FREE_SPINS'],
};

const canonicalFeatureStart: FeatureStartResponse = {
  sessionId: 's-2001',
  sessionVersion: 9,
  currentState: 'FREE_SPINS_LOOP',
  remainingFreeSpins: 10,
  activeFeatureView: null,
  availableActions: ['SPIN'],
};

describe('sessionStore', () => {
  beforeEach(() => {
    useSessionStore.getState().reset();
  });

  it('applyInitResponse mirrors every field of the canonical fixture', () => {
    useSessionStore.getState().applyInitResponse(canonicalInit);
    const s = useSessionStore.getState();

    expect(s.sessionId).toBe('s-2001');
    expect(s.sessionVersion).toBe(7);
    expect(s.gameId).toBe('aztec-fire');
    expect(s.mathVersion).toBe('v1');
    expect(s.currency).toBe('EUR');
    expect(s.currentState).toBe('FREE_SPINS_AWAITING');
    expect(s.remainingFreeSpins).toBe(10);
    expect(s.accumulatedFreeSpinsWin?.toPlain()).toBe(0);
    expect(s.currentBet?.toPlain()).toBe(1);
    expect(s.availableActions).toEqual(['START_FREE_SPINS']);
    expect(s.featureFlags).toEqual({ bonusBuyEnabled: true, powerBetEnabled: true });
    expect(s.activeFeatureView).toBeNull();
  });

  it.each([
    [7, 'no-op'],
    [6, 'no-op'],
    [8, 'applies'],
  ] as const)(
    'applySpinResponse with sessionVersion=%i %s relative to current=7',
    (incomingVersion, expectation) => {
      useSessionStore.getState().applyInitResponse(canonicalInit);
      const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});

      useSessionStore
        .getState()
        .applySpinResponse({ ...canonicalSpin, sessionVersion: incomingVersion });
      const s = useSessionStore.getState();

      if (expectation === 'no-op') {
        expect(s.sessionVersion).toBe(7);
        expect(s.lastSpin).toBeNull();
        expect(warn).toHaveBeenCalled();
      } else {
        expect(s.sessionVersion).toBe(incomingVersion);
        expect(s.lastSpin).not.toBeNull();
      }
      warn.mockRestore();
    },
  );

  it('applyFeatureStartResponse advances state and bumps the version', () => {
    useSessionStore.getState().applyInitResponse(canonicalInit);
    useSessionStore.getState().applyFeatureStartResponse(canonicalFeatureStart);
    const s = useSessionStore.getState();
    expect(s.currentState).toBe('FREE_SPINS_LOOP');
    expect(s.availableActions).toEqual(['SPIN']);
    expect(s.sessionVersion).toBe(9);
  });

  it('reset() returns the store to its initial empty state', () => {
    useSessionStore.getState().applyInitResponse(canonicalInit);
    useSessionStore.getState().reset();
    const s = useSessionStore.getState();
    expect(s.sessionId).toBeNull();
    expect(s.sessionVersion).toBeNull();
    expect(s.availableActions).toEqual([]);
  });
});
