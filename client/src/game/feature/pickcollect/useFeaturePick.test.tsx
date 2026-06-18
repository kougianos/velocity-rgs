import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import { HttpResponse, http as mswHttp } from 'msw';
import { setupServer } from 'msw/node';
import type { ReactNode } from 'react';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';

import { Money } from '@/common/money/Money';
import { handlers } from '@/mocks/handlers';
import { useSessionStore } from '@/session/sessionStore';

import { useFeaturePick } from './useFeaturePick';

function Wrap({ children }: { children: ReactNode }): JSX.Element {
  const qc = new QueryClient({ defaultOptions: { mutations: { retry: 0 } } });
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
}

function seedSession(state: Partial<ReturnType<typeof useSessionStore.getState>>): void {
  useSessionStore.setState({
    sessionId: 's-2001',
    sessionVersion: 12,
    gameId: 'aztec-fire',
    mathVersion: 'v1',
    currency: 'EUR',
    currentState: 'PICK_COLLECT_LOOP',
    remainingFreeSpins: 0,
    accumulatedFreeSpinsWin: Money.zero('EUR'),
    currentBet: Money.fromNumber(1.0, 'EUR'),
    availableActions: ['PICK'],
    featureFlags: { bonusBuyEnabled: true, powerBetEnabled: true },
    activeFeatureView: {
      boardSize: 12,
      openedPositions: [0, 1],
      revealedPicks: [],
      currentCollected: 0,
      totalFeatureWin: 0,
      remainingPicks: 3,
      status: 'IN_PROGRESS',
    },
    lastSpin: null,
    lastPick: null,
    ...state,
  });
}

const server = setupServer(...handlers);

describe('useFeaturePick', () => {
  beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
  afterEach(() => {
    server.resetHandlers();
    useSessionStore.getState().reset();
  });
  afterAll(() => server.close());

  beforeEach(() => {
    seedSession({});
  });

  it('sends the position + sessionVersion + Idempotency-Key and applies the response', async () => {
    let capturedKey: string | null = null;
    let capturedBody: unknown = null;
    server.use(
      mswHttp.post('*/api/v1/slot/feature/pick', async ({ request }) => {
        capturedKey = request.headers.get('Idempotency-Key');
        capturedBody = await request.json();
        return HttpResponse.json({
          sessionId: 's-2001',
          sessionVersion: 13,
          position: 4,
          resolvedTileType: 'MULTIPLIER',
          resolvedValue: 3,
          currentCollected: 45.0,
          remainingPicks: 2,
          featureCompleted: false,
          featureTotalWin: null,
          availableActions: ['PICK'],
        });
      }),
    );

    const { result } = renderHook(() => useFeaturePick(), { wrapper: Wrap });

    await new Promise<void>((resolve, reject) => {
      result.current.mutate(
        { position: 4 },
        { onSuccess: () => resolve(), onError: (e) => reject(e) },
      );
    });

    await waitFor(() => {
      expect(useSessionStore.getState().sessionVersion).toBe(13);
    });
    expect(capturedKey).toMatch(/^[0-9a-f-]{36}$/);
    expect(capturedBody).toMatchObject({
      gameId: 'aztec-fire',
      sessionId: 's-2001',
      sessionVersion: 12,
      position: 4,
    });
    expect(useSessionStore.getState().lastPick?.resolvedValue).toBe(3);
  });

  it('refuses to fire for an already-opened position', async () => {
    const { result } = renderHook(() => useFeaturePick(), { wrapper: Wrap });
    await expect(
      new Promise<void>((resolve, reject) => {
        result.current.mutate(
          { position: 0 },
          { onSuccess: () => resolve(), onError: (e) => reject(e) },
        );
      }),
    ).rejects.toThrow(/already opened/i);
  });

  it('throws when the session is not initialised', async () => {
    useSessionStore.getState().reset();
    const { result } = renderHook(() => useFeaturePick(), { wrapper: Wrap });
    await expect(
      new Promise<void>((resolve, reject) => {
        result.current.mutate(
          { position: 4 },
          { onSuccess: () => resolve(), onError: (e) => reject(e) },
        );
      }),
    ).rejects.toThrow(/not initialised/i);
  });
});
