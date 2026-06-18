import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { HttpResponse, http as mswHttp } from 'msw';
import { setupServer } from 'msw/node';
import type { ReactNode } from 'react';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';

import { Money } from '@/common/money/Money';
import { handlers } from '@/mocks/handlers';
import { useSessionStore } from '@/session/sessionStore';
import { useWalletStore } from '@/wallet/walletStore';

import { BonusBuyPanel } from './BonusBuyPanel';

function Wrap({ children }: { children: ReactNode }): JSX.Element {
  const qc = new QueryClient({ defaultOptions: { mutations: { retry: 0 } } });
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
}

function seedSession(state: Partial<ReturnType<typeof useSessionStore.getState>>): void {
  useSessionStore.setState({
    sessionId: 's-2001',
    sessionVersion: 8,
    gameId: 'aztec-fire',
    mathVersion: 'v1',
    currency: 'EUR',
    currentState: 'BASE_GAME',
    remainingFreeSpins: 0,
    accumulatedFreeSpinsWin: Money.zero('EUR'),
    currentBet: Money.fromNumber(1.0, 'EUR'),
    availableActions: ['SPIN', 'BUY_FEATURE'],
    featureFlags: { bonusBuyEnabled: true, powerBetEnabled: true },
    activeFeatureView: null,
    lastSpin: null,
    lastPick: null,
    ...state,
  });
}

function seedWalletBalance(balance: number): void {
  useWalletStore.setState({
    balance: Money.fromNumber(balance, 'EUR'),
    currency: 'EUR',
    lastUpdatedAt: new Date(),
  });
}

const server = setupServer(...handlers);

describe('BonusBuyPanel', () => {
  beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
  afterEach(() => {
    server.resetHandlers();
    useSessionStore.getState().reset();
    useWalletStore.getState().reset();
  });
  afterAll(() => server.close());

  it('returns null when bonusBuyEnabled is false', () => {
    seedSession({ featureFlags: { bonusBuyEnabled: false, powerBetEnabled: true } });
    seedWalletBalance(1000);
    const { container } = render(<Wrap><BonusBuyPanel /></Wrap>);
    expect(container).toBeEmptyDOMElement();
  });

  it('lists each bonus buy option with the display-only computed cost', () => {
    seedSession({});
    seedWalletBalance(1000);
    render(<Wrap><BonusBuyPanel /></Wrap>);
    expect(screen.getByText(/free spins/i)).toBeInTheDocument();
    expect(screen.getByText(/pick & collect/i)).toBeInTheDocument();
    // 1 EUR × 80 = 80 EUR; 1 EUR × 120 = 120 EUR
    expect(screen.getByText(/€80\.00/)).toBeInTheDocument();
    expect(screen.getByText(/€120\.00/)).toBeInTheDocument();
  });

  it('disables a buy button when the wallet cannot cover the cost', () => {
    seedSession({});
    seedWalletBalance(50);
    render(<Wrap><BonusBuyPanel /></Wrap>);
    // 1 EUR × 80 = 80 EUR but balance is 50, so disabled
    const fsBuy = screen.getByRole('button', { name: /buy free spins/i });
    expect(fsBuy).toBeDisabled();
    expect(fsBuy).toHaveAttribute('title', 'Not enough balance');
  });

  it('disables all buy buttons outside BASE_GAME', () => {
    seedSession({ currentState: 'FREE_SPINS_LOOP', availableActions: ['SPIN'] });
    seedWalletBalance(1000);
    render(<Wrap><BonusBuyPanel /></Wrap>);
    for (const btn of screen.getAllByRole('button', { name: /buy/i })) {
      expect(btn).toBeDisabled();
    }
  });

  it('opens the confirmation modal and triggers /feature/buy on confirm', async () => {
    seedSession({});
    seedWalletBalance(1000);
    let calls = 0;
    server.use(
      mswHttp.post('*/api/v1/slot/feature/buy', async () => {
        calls += 1;
        return HttpResponse.json({
          sessionId: 's-2001',
          sessionVersion: 9,
          buyType: 'FREE_SPINS_BUY',
          cost: 80.0,
          currency: 'EUR',
          enteredState: 'FREE_SPINS_AWAITING',
          featureInitPayload: { freeSpinsAwarded: 10 },
          availableActions: ['START_FREE_SPINS'],
        });
      }),
    );

    const user = userEvent.setup();
    render(<Wrap><BonusBuyPanel /></Wrap>);
    await user.click(screen.getByRole('button', { name: /buy free spins/i }));
    expect(screen.getByRole('dialog', { name: /confirm buy free spins/i })).toBeInTheDocument();

    await act(async () => {
      await user.click(screen.getByRole('button', { name: /^confirm$/i }));
    });
    await act(async () => {
      await new Promise((r) => setTimeout(r, 30));
    });

    expect(calls).toBe(1);
    expect(useSessionStore.getState().currentState).toBe('FREE_SPINS_AWAITING');
  });
});
