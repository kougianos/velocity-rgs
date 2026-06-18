import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { HttpResponse, http as mswHttp } from 'msw';
import { setupServer } from 'msw/node';
import type { ReactNode } from 'react';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';

import { useAuthStore } from '@/auth/authStore';
import { Money } from '@/common/money/Money';
import { handlers } from '@/mocks/handlers';
import { useSessionStore } from '@/session/sessionStore';

import { SpinButton } from './SpinButton';

function encodeBase64Url(input: string): string {
  return btoa(input).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function makeJwt(payload: Record<string, unknown>): string {
  const header = encodeBase64Url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const body = encodeBase64Url(JSON.stringify(payload));
  return `${header}.${body}.sig`;
}

function playerToken(): string {
  return makeJwt({
    sub: 'p-1001',
    sid: 's-2001',
    cur: 'EUR',
    exp: Math.floor(Date.now() / 1000) + 3600,
    roles: ['PLAYER'],
  });
}

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
    currentState: 'BASE_GAME',
    currentBet: Money.fromNumber(1.0, 'EUR'),
    availableActions: ['SPIN'],
    featureFlags: { bonusBuyEnabled: true, powerBetEnabled: true },
    ...state,
  });
}

const server = setupServer(...handlers);

describe('SpinButton', () => {
  beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
  afterEach(() => server.resetHandlers());
  afterAll(() => server.close());

  beforeEach(() => {
    useAuthStore.getState().clear();
    useSessionStore.getState().reset();
    useAuthStore.getState().setToken(playerToken());
  });

  it('is disabled when availableActions does not include SPIN', () => {
    seedSession({ currentState: 'FREE_SPINS_AWAITING', availableActions: ['START_FREE_SPINS'] });
    render(
      <Wrap>
        <SpinButton />
      </Wrap>,
    );
    const btn = screen.getByRole('button', { name: /spin/i });
    expect(btn).toBeDisabled();
    expect(btn).toHaveAttribute('title', expect.stringMatching(/free spins/i));
  });

  it('issues exactly one POST /spin for rapid double-clicks', async () => {
    seedSession({});
    let calls = 0;
    server.use(
      mswHttp.post('*/api/v1/slot/spin', async () => {
        calls += 1;
        // Hold the response open long enough that the second click lands
        // while the button is in `isPending`.
        await new Promise((r) => setTimeout(r, 50));
        return HttpResponse.json({
          sessionId: 's-2001',
          sessionVersion: 8,
          roundId: 'r-3001',
          mathVersion: 'v1',
          betDebited: 1.0,
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
        });
      }),
    );

    const user = userEvent.setup();
    render(
      <Wrap>
        <SpinButton />
      </Wrap>,
    );

    const btn = screen.getByRole('button', { name: /spin/i });
    await act(async () => {
      await user.click(btn);
      await user.click(btn);
    });

    // Wait until the inflight resolves and the store updates.
    await act(async () => {
      await new Promise((r) => setTimeout(r, 120));
    });

    expect(calls).toBe(1);
  });
});
