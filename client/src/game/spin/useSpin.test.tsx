import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import { HttpResponse, http as mswHttp } from 'msw';
import { setupServer } from 'msw/node';
import type { ReactNode } from 'react';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';

import { useAuthStore } from '@/auth/authStore';
import { Money } from '@/common/money/Money';
import { handlers } from '@/mocks/handlers';
import { useSessionStore } from '@/session/sessionStore';

import { useSpin } from './useSpin';

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

function makeWrapper(qc: QueryClient): (props: { children: ReactNode }) => JSX.Element {
  function Wrapper({ children }: { children: ReactNode }): JSX.Element {
    return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
  }
  return Wrapper;
}

function seedSession(): void {
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
  });
}

const server = setupServer(...handlers);

describe('useSpin', () => {
  beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
  afterEach(() => server.resetHandlers());
  afterAll(() => server.close());

  beforeEach(() => {
    useAuthStore.getState().clear();
    useSessionStore.getState().reset();
    useAuthStore.getState().setToken(playerToken());
    seedSession();
  });

  it('sends sessionVersion + bet from the store and applies the response', async () => {
    let received: { body: unknown; key: string | null } | null = null;
    server.use(
      mswHttp.post('*/api/v1/slot/spin', async ({ request }) => {
        received = {
          body: await request.json(),
          key: request.headers.get('Idempotency-Key'),
        };
        return HttpResponse.json({
          sessionId: 's-2001',
          sessionVersion: 8,
          roundId: 'r-3001',
          mathVersion: 'v1',
          betDebited: 1.0,
          totalWin: 150.0,
          matrix: [
            [2, 5, 1, 8, 9],
            [3, 12, 1, 1, 4],
            [7, 8, 2, 3, 11],
          ],
          stopPositions: [14, 82, 4, 119, 43],
          winLines: [{ lineId: 3, symbolId: 1, count: 4, payout: 150.0 }],
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
            accumulatedFreeSpinsWin: 150.0,
          },
          availableActions: ['START_FREE_SPINS'],
        });
      }),
    );

    const qc = new QueryClient({ defaultOptions: { mutations: { retry: 0 } } });
    const { result } = renderHook(() => useSpin(), { wrapper: makeWrapper(qc) });

    await act(async () => {
      await result.current.mutateAsync({ powerBetActive: false });
    });

    expect(received).not.toBeNull();
    expect(received!.body).toMatchObject({
      gameId: 'aztec-fire',
      sessionId: 's-2001',
      sessionVersion: 7,
      betSize: 1.0,
      powerBetActive: false,
    });
    expect(received!.key).toMatch(/^[0-9a-f-]{36}$/);

    expect(useSessionStore.getState().sessionVersion).toBe(8);
    expect(useSessionStore.getState().currentState).toBe('FREE_SPINS_AWAITING');
    expect(useSessionStore.getState().lastSpin?.totalWin).toBe(150.0);
  });

  it('uses a fresh Idempotency-Key for each successful invocation', async () => {
    const seenKeys: string[] = [];
    server.use(
      mswHttp.post('*/api/v1/slot/spin', ({ request }) => {
        const key = request.headers.get('Idempotency-Key');
        if (key) seenKeys.push(key);
        return HttpResponse.json({
          sessionId: 's-2001',
          sessionVersion: useSessionStore.getState().sessionVersion! + 1,
          roundId: `r-${seenKeys.length}`,
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

    const qc = new QueryClient({ defaultOptions: { mutations: { retry: 0 } } });
    const { result } = renderHook(() => useSpin(), { wrapper: makeWrapper(qc) });

    await act(async () => {
      await result.current.mutateAsync({ powerBetActive: false });
    });
    await act(async () => {
      await result.current.mutateAsync({ powerBetActive: false });
    });

    expect(seenKeys).toHaveLength(2);
    expect(seenKeys[0]).not.toBe(seenKeys[1]);
  });

  it('reuses the Idempotency-Key on transport failure, then retries with the same key', async () => {
    const seenKeys: string[] = [];
    let attempt = 0;
    server.use(
      mswHttp.post('*/api/v1/slot/spin', ({ request }) => {
        const key = request.headers.get('Idempotency-Key');
        if (key) seenKeys.push(key);
        attempt += 1;
        if (attempt === 1) return HttpResponse.error();
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

    const qc = new QueryClient({ defaultOptions: { mutations: { retry: 0 } } });
    const { result } = renderHook(() => useSpin(), { wrapper: makeWrapper(qc) });

    await act(async () => {
      try {
        await result.current.mutateAsync({ powerBetActive: false });
      } catch {
        /* expected transport failure */
      }
    });

    await act(async () => {
      await result.current.mutateAsync({ powerBetActive: false });
    });

    expect(seenKeys).toHaveLength(2);
    expect(seenKeys[0]).toBe(seenKeys[1]);
  });

  it('uses a fresh Idempotency-Key after a definitive 4xx response', async () => {
    const seenKeys: string[] = [];
    let attempt = 0;
    server.use(
      mswHttp.post('*/api/v1/slot/spin', ({ request }) => {
        const key = request.headers.get('Idempotency-Key');
        if (key) seenKeys.push(key);
        attempt += 1;
        if (attempt === 1) {
          return HttpResponse.json(
            {
              code: 'INSUFFICIENT_FUNDS',
              message: 'Wallet debit failed',
              httpStatus: 409,
              traceId: 't-1',
              timestamp: '2026-06-17T10:15:30Z',
            },
            { status: 409 },
          );
        }
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

    const qc = new QueryClient({ defaultOptions: { mutations: { retry: 0 } } });
    const { result } = renderHook(() => useSpin(), { wrapper: makeWrapper(qc) });

    await act(async () => {
      try {
        await result.current.mutateAsync({ powerBetActive: false });
      } catch {
        /* expected 409 */
      }
    });

    await act(async () => {
      await result.current.mutateAsync({ powerBetActive: false });
    });

    expect(seenKeys).toHaveLength(2);
    expect(seenKeys[0]).not.toBe(seenKeys[1]);
  });

  it('leaves the session store untouched on a 4xx', async () => {
    server.use(
      mswHttp.post('*/api/v1/slot/spin', () =>
        HttpResponse.json(
          {
            code: 'INSUFFICIENT_FUNDS',
            message: 'Wallet debit failed',
            httpStatus: 409,
            traceId: 't-1',
            timestamp: '2026-06-17T10:15:30Z',
          },
          { status: 409 },
        ),
      ),
    );

    const qc = new QueryClient({ defaultOptions: { mutations: { retry: 0 } } });
    const { result } = renderHook(() => useSpin(), { wrapper: makeWrapper(qc) });

    await act(async () => {
      try {
        await result.current.mutateAsync({ powerBetActive: false });
      } catch {
        /* expected */
      }
    });

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(useSessionStore.getState().sessionVersion).toBe(7);
    expect(useSessionStore.getState().lastSpin).toBeNull();
  });
});
