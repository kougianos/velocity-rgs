import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';

import { Money } from '@/common/money/Money';
import { useUiStore } from '@/game/ui/uiStore';
import { useSessionStore } from '@/session/sessionStore';

import { PowerBetToggle } from './PowerBetToggle';

function seedSession(state: Partial<ReturnType<typeof useSessionStore.getState>>): void {
  useSessionStore.setState({
    sessionId: 's-2001',
    sessionVersion: 7,
    gameId: 'aztec-fire',
    mathVersion: 'v1',
    currency: 'EUR',
    currentState: 'BASE_GAME',
    remainingFreeSpins: 0,
    accumulatedFreeSpinsWin: Money.zero('EUR'),
    currentBet: Money.fromNumber(1.0, 'EUR'),
    availableActions: ['SPIN'],
    featureFlags: { bonusBuyEnabled: true, powerBetEnabled: true },
    activeFeatureView: null,
    lastSpin: null,
    lastPick: null,
    ...state,
  });
}

describe('PowerBetToggle', () => {
  afterEach(() => {
    useSessionStore.getState().reset();
    useUiStore.getState().reset();
  });

  it('returns null when powerBetEnabled flag is false', () => {
    seedSession({ featureFlags: { bonusBuyEnabled: true, powerBetEnabled: false } });
    const { container } = render(<PowerBetToggle />);
    expect(container).toBeEmptyDOMElement();
  });

  it('toggles the uiStore.powerBetActive flag on click', async () => {
    seedSession({});
    render(<PowerBetToggle />);
    const sw = screen.getByRole('switch', { name: /power bet/i });
    expect(sw).toHaveAttribute('aria-checked', 'false');
    await userEvent.click(sw);
    expect(useUiStore.getState().powerBetActive).toBe(true);
    expect(sw).toHaveAttribute('aria-checked', 'true');
  });

  it('is disabled outside BASE_GAME', () => {
    seedSession({ currentState: 'FREE_SPINS_LOOP' });
    render(<PowerBetToggle />);
    expect(screen.getByRole('switch', { name: /power bet/i })).toBeDisabled();
  });

  it('shows the multiplier caption when active in BASE_GAME', async () => {
    seedSession({});
    render(<PowerBetToggle />);
    await userEvent.click(screen.getByRole('switch', { name: /power bet/i }));
    expect(
      screen.getByText(/power bet active — bet multiplier 1\.5×/i),
    ).toBeInTheDocument();
  });

  it('hides the caption when disabled even if internally active', () => {
    useUiStore.getState().setPowerBetActive(true);
    seedSession({ currentState: 'FREE_SPINS_LOOP' });
    render(<PowerBetToggle />);
    expect(screen.queryByText(/power bet active/i)).not.toBeInTheDocument();
  });
});
