import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';

import { Money } from '@/common/money/Money';
import { useSessionStore } from '@/session/sessionStore';

import { BetSelector } from './BetSelector';

function seedSession(currentState: 'BASE_GAME' | 'FREE_SPINS_LOOP', bet = 1): void {
  useSessionStore.setState({
    sessionId: 's-2001',
    sessionVersion: 7,
    gameId: 'aztec-fire',
    mathVersion: 'v1',
    currency: 'EUR',
    currentState,
    remainingFreeSpins: 0,
    accumulatedFreeSpinsWin: Money.zero('EUR'),
    currentBet: Money.fromNumber(bet, 'EUR'),
    availableActions: ['SPIN'],
    featureFlags: { bonusBuyEnabled: true, powerBetEnabled: true },
    activeFeatureView: null,
    lastSpin: null,
    lastPick: null,
  });
}

describe('BetSelector', () => {
  afterEach(() => {
    useSessionStore.getState().reset();
  });

  it('renders both step buttons disabled while in FREE_SPINS_LOOP', () => {
    seedSession('FREE_SPINS_LOOP');
    render(<BetSelector />);
    expect(screen.getByRole('button', { name: /decrease bet/i })).toBeDisabled();
    expect(screen.getByRole('button', { name: /increase bet/i })).toBeDisabled();
    expect(screen.getByRole('group', { name: /bet selector/i })).toHaveAttribute(
      'aria-disabled',
      'true',
    );
  });

  it('steps to the next ladder entry on +', async () => {
    seedSession('BASE_GAME', 1);
    render(<BetSelector />);
    await userEvent.click(screen.getByRole('button', { name: /increase bet/i }));
    expect(useSessionStore.getState().currentBet?.toPlain()).toBe(2);
  });

  it('clamps at the lower bound', async () => {
    seedSession('BASE_GAME', 0.2);
    render(<BetSelector />);
    expect(screen.getByRole('button', { name: /decrease bet/i })).toBeDisabled();
  });
});
