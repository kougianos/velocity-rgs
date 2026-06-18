import { render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import type { SpinResponse } from '@/api/slot/spin';
import { Money } from '@/common/money/Money';
import { useSessionStore } from '@/session/sessionStore';

import { ReasonCodeBanner } from './ReasonCodeBanner';

function makeSpin(overrides: Partial<SpinResponse>): SpinResponse {
  return {
    sessionId: 's-2001',
    sessionVersion: 12,
    roundId: 'r-3010',
    mathVersion: 'v1',
    betDebited: 0,
    totalWin: 0,
    matrix: [
      [1, 1, 1, 1, 1],
      [1, 1, 1, 1, 1],
      [1, 1, 1, 1, 1],
    ],
    stopPositions: [0, 0, 0, 0, 0],
    winLines: [],
    featuresTriggered: {
      freeSpinsAwarded: 0,
      isPowerBetActive: false,
      pickCollectTriggered: false,
      bonusBuyExecuted: false,
      reasonCodes: [],
    },
    sessionState: {
      currentState: 'FREE_SPINS_LOOP',
      remainingFreeSpins: 5,
      accumulatedFreeSpinsWin: 0,
    },
    availableActions: ['SPIN'],
    ...overrides,
  } as SpinResponse;
}

function seed(lastSpin: SpinResponse): void {
  useSessionStore.setState({
    sessionId: 's-2001',
    sessionVersion: lastSpin.sessionVersion,
    gameId: 'aztec-fire',
    mathVersion: 'v1',
    currency: 'EUR',
    currentState: lastSpin.sessionState.currentState,
    remainingFreeSpins: lastSpin.sessionState.remainingFreeSpins,
    accumulatedFreeSpinsWin: Money.fromNumber(lastSpin.sessionState.accumulatedFreeSpinsWin, 'EUR'),
    currentBet: Money.fromNumber(1.0, 'EUR'),
    availableActions: [...lastSpin.availableActions],
    featureFlags: { bonusBuyEnabled: true, powerBetEnabled: true },
    activeFeatureView: null,
    lastSpin,
    lastPick: null,
  });
}

describe('ReasonCodeBanner — Free Spins retrigger (M6)', () => {
  afterEach(() => {
    useSessionStore.getState().reset();
  });

  it('renders "+N Free Spins!" using freeSpinsAwarded as the delta', () => {
    seed(
      makeSpin({
        sessionVersion: 14,
        featuresTriggered: {
          freeSpinsAwarded: 5,
          isPowerBetActive: false,
          pickCollectTriggered: false,
          bonusBuyExecuted: false,
          reasonCodes: ['RETRIGGERED_FREE_SPINS'],
        },
      }),
    );
    render(<ReasonCodeBanner />);
    expect(screen.getByRole('status')).toHaveTextContent(/\+5 free spins/i);
  });

  it('renders "Free Spins triggered!" on the initial scatter trigger', () => {
    seed(
      makeSpin({
        sessionVersion: 8,
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
          accumulatedFreeSpinsWin: 0,
        },
      }),
    );
    render(<ReasonCodeBanner />);
    expect(screen.getByRole('status')).toHaveTextContent(/free spins triggered/i);
  });
});
