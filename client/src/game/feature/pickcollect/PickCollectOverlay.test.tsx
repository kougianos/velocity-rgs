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

import { PickCollectOverlay } from './PickCollectOverlay';

function Wrap({ children }: { children: ReactNode }): JSX.Element {
  const qc = new QueryClient({ defaultOptions: { mutations: { retry: 0 } } });
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
}

function seedSession(state: Partial<ReturnType<typeof useSessionStore.getState>>): void {
  useSessionStore.setState({
    sessionId: 's-2001',
    sessionVersion: 9,
    gameId: 'aztec-fire',
    mathVersion: 'v1',
    currency: 'EUR',
    currentState: 'PICK_COLLECT_AWAITING',
    remainingFreeSpins: 0,
    accumulatedFreeSpinsWin: Money.zero('EUR'),
    currentBet: Money.fromNumber(1.0, 'EUR'),
    availableActions: ['START_PICK_COLLECT'],
    featureFlags: { bonusBuyEnabled: true, powerBetEnabled: true },
    activeFeatureView: null,
    lastSpin: null,
    lastPick: null,
    ...state,
  });
}

const server = setupServer(...handlers);

describe('PickCollectOverlay', () => {
  beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
  afterEach(() => {
    server.resetHandlers();
    useSessionStore.getState().reset();
  });
  afterAll(() => server.close());

  it('renders null in BASE_GAME', () => {
    seedSession({ currentState: 'BASE_GAME', availableActions: ['SPIN'] });
    const { container } = render(<Wrap><PickCollectOverlay /></Wrap>);
    expect(container).toBeEmptyDOMElement();
  });

  it('renders the awaiting CTA in PICK_COLLECT_AWAITING', () => {
    seedSession({});
    render(<Wrap><PickCollectOverlay /></Wrap>);
    expect(
      screen.getByRole('region', { name: /pick and collect awaiting/i }),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /start pick & collect/i })).toBeEnabled();
  });

  it('disables Start when START_PICK_COLLECT is not in availableActions', () => {
    seedSession({ availableActions: [] });
    render(<Wrap><PickCollectOverlay /></Wrap>);
    expect(screen.getByRole('button', { name: /start pick & collect/i })).toBeDisabled();
  });

  it('issues exactly one POST /feature/start for rapid double-clicks', async () => {
    seedSession({});
    let calls = 0;
    server.use(
      mswHttp.post('*/api/v1/slot/feature/start', async () => {
        calls += 1;
        await new Promise((r) => setTimeout(r, 40));
        return HttpResponse.json({
          sessionId: 's-2001',
          sessionVersion: 10,
          currentState: 'PICK_COLLECT_LOOP',
          remainingFreeSpins: 0,
          activeFeatureView: {
            boardSize: 12,
            openedPositions: [],
            revealedPicks: [],
            currentCollected: 0,
            totalFeatureWin: 0,
            remainingPicks: 5,
            status: 'IN_PROGRESS',
          },
          availableActions: ['PICK'],
        });
      }),
    );

    const user = userEvent.setup();
    render(<Wrap><PickCollectOverlay /></Wrap>);
    const btn = screen.getByRole('button', { name: /start pick & collect/i });
    await act(async () => {
      await user.click(btn);
      await user.click(btn);
    });
    await act(async () => {
      await new Promise((r) => setTimeout(r, 100));
    });

    expect(calls).toBe(1);
    expect(useSessionStore.getState().currentState).toBe('PICK_COLLECT_LOOP');
  });

  it('renders the in-loop badge with remaining picks', () => {
    seedSession({
      currentState: 'PICK_COLLECT_LOOP',
      availableActions: ['PICK'],
      activeFeatureView: {
        boardSize: 12,
        openedPositions: [0, 1],
        revealedPicks: [],
        currentCollected: 0,
        totalFeatureWin: 0,
        remainingPicks: 3,
        status: 'IN_PROGRESS',
      },
    });
    render(<Wrap><PickCollectOverlay /></Wrap>);
    const badge = screen.getByRole('status', { name: /pick and collect active/i });
    expect(badge).toHaveTextContent('3');
  });
});
