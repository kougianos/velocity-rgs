import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import { HttpResponse, http as mswHttp } from 'msw';
import { setupServer } from 'msw/node';
import type { ReactNode } from 'react';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';

import { useAuthStore } from '@/auth/authStore';
import { handlers } from '@/mocks/handlers';
import { useSessionStore } from '@/session/sessionStore';

import { useWalletBalance } from './useWalletBalance';
import { useWalletStore } from './walletStore';

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

const server = setupServer(...handlers);

describe('useWalletBalance', () => {
  beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
  afterEach(() => server.resetHandlers());
  afterAll(() => server.close());

  beforeEach(() => {
    useAuthStore.getState().clear();
    useSessionStore.getState().reset();
    useWalletStore.getState().reset();
  });

  it('fetches the balance once authenticated and pipes it into walletStore', async () => {
    useAuthStore.getState().setToken(playerToken());
    const qc = new QueryClient({ defaultOptions: { queries: { retry: 0 } } });
    renderHook(() => useWalletBalance(), { wrapper: makeWrapper(qc) });

    await waitFor(() => {
      expect(useWalletStore.getState().balance?.toPlain()).toBe(98.5);
      expect(useWalletStore.getState().currency).toBe('EUR');
    });
  });

  it('refetches when sessionVersion bumps (post-spin reconciliation)', async () => {
    useAuthStore.getState().setToken(playerToken());
    let calls = 0;
    server.use(
      mswHttp.get('*/api/v1/wallet/balance', () => {
        calls += 1;
        return HttpResponse.json({
          playerId: 'p-1001',
          balance: calls === 1 ? 100 : 98.5,
          currency: 'EUR',
        });
      }),
    );

    const qc = new QueryClient({ defaultOptions: { queries: { retry: 0 } } });
    renderHook(() => useWalletBalance(), { wrapper: makeWrapper(qc) });

    await waitFor(() => expect(calls).toBeGreaterThanOrEqual(1));
    const firstCalls = calls;

    // simulate a successful spin: sessionVersion bump should trigger a refetch
    useSessionStore.setState({ sessionVersion: 8 });

    await waitFor(() => expect(calls).toBeGreaterThan(firstCalls), { timeout: 500 });
    await waitFor(() => expect(useWalletStore.getState().balance?.toPlain()).toBe(98.5));
  });
});
