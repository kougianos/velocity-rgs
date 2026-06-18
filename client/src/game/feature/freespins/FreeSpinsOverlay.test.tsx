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

import { FreeSpinsOverlay } from './FreeSpinsOverlay';

function Wrap({ children }: { children: ReactNode }): JSX.Element {
  const qc = new QueryClient({ defaultOptions: { mutations: { retry: 0 } } });
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
}

function seedSession(state: Partial<ReturnType<typeof useSessionStore.getState>>): void {
  useSessionStore.setState({
    sessionId: 's-2001',
    sessionVersion: 7,
    gameId: 'aztec-fire',
    mathVersion: 'v1',
    currency: 'EUR',
    currentState: 'FREE_SPINS_AWAITING',
    remainingFreeSpins: 10,
    accumulatedFreeSpinsWin: Money.zero('EUR'),
    currentBet: Money.fromNumber(1.0, 'EUR'),
    availableActions: ['START_FREE_SPINS'],
    featureFlags: { bonusBuyEnabled: true, powerBetEnabled: true },
    activeFeatureView: null,
    lastSpin: null,
    lastPick: null,
    ...state,
  });
}

const server = setupServer(...handlers);

describe('FreeSpinsOverlay', () => {
  beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
  afterEach(() => {
    server.resetHandlers();
    useSessionStore.getState().reset();
  });
  afterAll(() => server.close());

  it('renders null in BASE_GAME', () => {
    seedSession({ currentState: 'BASE_GAME', availableActions: ['SPIN'] });
    const { container } = render(<Wrap><FreeSpinsOverlay /></Wrap>);
    expect(container).toBeEmptyDOMElement();
  });

  it('renders the awaiting CTA when in FREE_SPINS_AWAITING', () => {
    seedSession({});
    render(<Wrap><FreeSpinsOverlay /></Wrap>);
    expect(
      screen.getByRole('region', { name: /free spins awaiting/i }),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /start free spins/i })).toBeEnabled();
  });

  it('disables the Start button when START_FREE_SPINS is not in availableActions', () => {
    seedSession({ availableActions: [] });
    render(<Wrap><FreeSpinsOverlay /></Wrap>);
    expect(screen.getByRole('button', { name: /start free spins/i })).toBeDisabled();
  });

  it('issues exactly one POST /feature/start for rapid double-clicks', async () => {
    seedSession({});
    let calls = 0;
    server.use(
      mswHttp.post('*/api/v1/slot/feature/start', async () => {
        calls += 1;
        await new Promise((r) => setTimeout(r, 50));
        return HttpResponse.json({
          sessionId: 's-2001',
          sessionVersion: 9,
          currentState: 'FREE_SPINS_LOOP',
          remainingFreeSpins: 10,
          activeFeatureView: null,
          availableActions: ['SPIN'],
        });
      }),
    );

    const user = userEvent.setup();
    render(<Wrap><FreeSpinsOverlay /></Wrap>);
    const btn = screen.getByRole('button', { name: /start free spins/i });
    await act(async () => {
      await user.click(btn);
      await user.click(btn);
    });
    await act(async () => {
      await new Promise((r) => setTimeout(r, 120));
    });

    expect(calls).toBe(1);
    expect(useSessionStore.getState().currentState).toBe('FREE_SPINS_LOOP');
  });

  it('renders the in-loop badge with remaining and accumulated', () => {
    seedSession({
      currentState: 'FREE_SPINS_LOOP',
      remainingFreeSpins: 7,
      accumulatedFreeSpinsWin: Money.fromNumber(42.5, 'EUR'),
      availableActions: ['SPIN'],
    });
    render(<Wrap><FreeSpinsOverlay /></Wrap>);
    const badge = screen.getByRole('status', { name: /free spins active/i });
    expect(badge).toHaveTextContent('7');
    expect(badge).toHaveTextContent(/€42\.50/);
  });
});
