import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import { HttpResponse, http as mswHttp } from 'msw';
import { setupServer } from 'msw/node';
import type { ReactNode } from 'react';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';

import { Money } from '@/common/money/Money';
import { handlers } from '@/mocks/handlers';
import { useSessionStore } from '@/session/sessionStore';

import { useStartFeature } from './useStartFeature';

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
    currentState: 'FREE_SPINS_AWAITING',
    remainingFreeSpins: 10,
    accumulatedFreeSpinsWin: Money.zero('EUR'),
    currentBet: Money.fromNumber(1.0, 'EUR'),
    availableActions: ['START_FREE_SPINS'],
    featureFlags: { bonusBuyEnabled: true, powerBetEnabled: true },
    activeFeatureView: null,
    lastSpin: null,
    lastPick: null,
  });
}

const server = setupServer(...handlers);

describe('useStartFeature', () => {
  beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
  afterEach(() => {
    server.resetHandlers();
    useSessionStore.getState().reset();
  });
  afterAll(() => server.close());

  beforeEach(() => {
    seedSession();
  });

  it('sends a fresh Idempotency-Key header and applies the response to the session store', async () => {
    let capturedKey: string | null = null;
    server.use(
      mswHttp.post('*/api/v1/slot/feature/start', ({ request }) => {
        capturedKey = request.headers.get('Idempotency-Key');
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

    const { result } = renderHook(() => useStartFeature(), { wrapper: Wrap });

    await new Promise<void>((resolve, reject) => {
      result.current.mutate(
        { featureType: 'FREE_SPINS' },
        { onSuccess: () => resolve(), onError: (e) => reject(e) },
      );
    });

    await waitFor(() => {
      expect(useSessionStore.getState().currentState).toBe('FREE_SPINS_LOOP');
    });
    expect(capturedKey).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i);
    expect(useSessionStore.getState().sessionVersion).toBe(9);
    expect(useSessionStore.getState().availableActions).toEqual(['SPIN']);
  });

  it('throws when the session is not initialised', async () => {
    useSessionStore.getState().reset();
    const { result } = renderHook(() => useStartFeature(), { wrapper: Wrap });
    await expect(
      new Promise<void>((resolve, reject) => {
        result.current.mutate(
          { featureType: 'FREE_SPINS' },
          { onSuccess: () => resolve(), onError: (e) => reject(e) },
        );
      }),
    ).rejects.toThrow(/not initialised/i);
  });
});
