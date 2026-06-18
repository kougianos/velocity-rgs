import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import { HttpResponse, http as mswHttp } from 'msw';
import { setupServer } from 'msw/node';
import type { ReactNode } from 'react';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';

import { Money } from '@/common/money/Money';
import { handlers } from '@/mocks/handlers';
import { useSessionStore } from '@/session/sessionStore';

import { useBuyFeature } from './useBuyFeature';

function Wrap({ children }: { children: ReactNode }): JSX.Element {
  const qc = new QueryClient({ defaultOptions: { mutations: { retry: 0 } } });
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
}

function seedSession(): void {
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
  });
}

const server = setupServer(...handlers);

describe('useBuyFeature', () => {
  beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
  afterEach(() => {
    server.resetHandlers();
    useSessionStore.getState().reset();
  });
  afterAll(() => server.close());

  beforeEach(() => {
    seedSession();
  });

  it('sends the bet + buyType with a fresh Idempotency-Key and applies the response', async () => {
    let capturedKey: string | null = null;
    let capturedBody: unknown = null;
    server.use(
      mswHttp.post('*/api/v1/slot/feature/buy', async ({ request }) => {
        capturedKey = request.headers.get('Idempotency-Key');
        capturedBody = await request.json();
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

    const { result } = renderHook(() => useBuyFeature(), { wrapper: Wrap });

    await new Promise<void>((resolve, reject) => {
      result.current.mutate(
        { buyType: 'FREE_SPINS_BUY', betSize: 1.0 },
        { onSuccess: () => resolve(), onError: (e) => reject(e) },
      );
    });

    await waitFor(() => {
      expect(useSessionStore.getState().currentState).toBe('FREE_SPINS_AWAITING');
    });
    expect(capturedKey).toMatch(/^[0-9a-f-]{36}$/);
    expect(capturedBody).toMatchObject({
      gameId: 'aztec-fire',
      sessionId: 's-2001',
      sessionVersion: 8,
      buyType: 'FREE_SPINS_BUY',
      betSize: 1.0,
    });
    expect(useSessionStore.getState().sessionVersion).toBe(9);
    expect(useSessionStore.getState().availableActions).toEqual(['START_FREE_SPINS']);
  });

  it('throws when the session is not initialised', async () => {
    useSessionStore.getState().reset();
    const { result } = renderHook(() => useBuyFeature(), { wrapper: Wrap });
    await expect(
      new Promise<void>((resolve, reject) => {
        result.current.mutate(
          { buyType: 'FREE_SPINS_BUY', betSize: 1.0 },
          { onSuccess: () => resolve(), onError: (e) => reject(e) },
        );
      }),
    ).rejects.toThrow(/not initialised/i);
  });
});
