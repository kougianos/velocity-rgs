import { act, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { useSessionStore } from '@/session/sessionStore';

import { SpinAnnouncer } from './SpinAnnouncer';

describe('SpinAnnouncer', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    useSessionStore.getState().reset();
    useSessionStore.setState({ currency: 'EUR' });
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('exposes an aria-live polite region that starts empty', () => {
    render(<SpinAnnouncer />);
    const live = screen.getByRole('status');
    expect(live).toHaveAttribute('aria-live', 'polite');
    expect(live).toHaveTextContent('');
  });

  it('announces wins after the debounce window', () => {
    render(<SpinAnnouncer />);
    act(() => {
      useSessionStore.setState({
        lastSpin: {
          sessionId: 's-2001',
          sessionVersion: 8,
          roundId: 'r-3001',
          mathVersion: 'v1',
          betDebited: 1,
          totalWin: 1.5,
          matrix: [
            [1, 1, 1, 1, 1],
            [1, 1, 1, 1, 1],
            [1, 1, 1, 1, 1],
          ],
          stopPositions: [0, 0, 0, 0, 0],
          winLines: [{ lineId: 3, symbolId: 1, count: 4, payout: 1.5 }],
          featuresTriggered: {
            freeSpinsAwarded: 0,
            isPowerBetActive: false,
            pickCollectTriggered: false,
            bonusBuyExecuted: false,
            reasonCodes: [],
          },
          sessionState: {
            currentState: 'BASE_GAME',
            remainingFreeSpins: 0,
            accumulatedFreeSpinsWin: 0,
          },
          availableActions: ['SPIN'],
        },
      });
    });
    act(() => {
      vi.advanceTimersByTime(450);
    });
    expect(screen.getByRole('status')).toHaveTextContent(/win: 1\.50 EUR on line 1/i);
  });

  it('announces "no win" when totalWin is 0', () => {
    render(<SpinAnnouncer />);
    act(() => {
      useSessionStore.setState({
        lastSpin: {
          sessionId: 's',
          sessionVersion: 1,
          roundId: 'r',
          mathVersion: 'v1',
          betDebited: 1,
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
            currentState: 'BASE_GAME',
            remainingFreeSpins: 0,
            accumulatedFreeSpinsWin: 0,
          },
          availableActions: ['SPIN'],
        },
      });
    });
    act(() => {
      vi.advanceTimersByTime(450);
    });
    expect(screen.getByRole('status')).toHaveTextContent(/no win/i);
  });

  it('announces picks during PICK_COLLECT_LOOP', () => {
    render(<SpinAnnouncer />);
    act(() => {
      useSessionStore.setState({
        lastPick: {
          sessionId: 's',
          sessionVersion: 2,
          position: 4,
          resolvedTileType: 'MULTIPLIER',
          resolvedValue: 3,
          currentCollected: 45,
          remainingPicks: 2,
          featureCompleted: false,
          featureTotalWin: null,
          currentState: 'PICK_COLLECT_LOOP',
          availableActions: ['PICK'],
        },
      });
    });
    act(() => {
      vi.advanceTimersByTime(450);
    });
    expect(screen.getByRole('status')).toHaveTextContent(/picked MULTIPLIER 3/i);
  });

  it('announces feature completion on the final pick', () => {
    render(<SpinAnnouncer />);
    act(() => {
      useSessionStore.setState({
        lastPick: {
          sessionId: 's',
          sessionVersion: 3,
          position: 7,
          resolvedTileType: 'CREDITS',
          resolvedValue: 12,
          currentCollected: 87,
          remainingPicks: 0,
          featureCompleted: true,
          featureTotalWin: 87,
          currentState: 'BASE_GAME',
          availableActions: ['SPIN'],
        },
      });
    });
    act(() => {
      vi.advanceTimersByTime(450);
    });
    expect(screen.getByRole('status')).toHaveTextContent(/pick & collect complete/i);
  });
});
