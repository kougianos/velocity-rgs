import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import { HttpResponse, http as mswHttp } from 'msw';
import { setupServer } from 'msw/node';
import { MemoryRouter } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';

import { RgsHttpError } from '@/api/http/errors';
import { useAuthStore } from '@/auth/authStore';
import { handlers } from '@/mocks/handlers';
import { notifyRgsError } from '@/session/errorBus';
import { useSessionStore } from '@/session/sessionStore';

import { App } from './App';
import { createQueryClient } from './queryClient';

function encodeBase64Url(input: string): string {
  return btoa(input).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function makeJwt(payload: Record<string, unknown>): string {
  const header = encodeBase64Url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const body = encodeBase64Url(JSON.stringify(payload));
  return `${header}.${body}.sig`;
}

const futureExp = Math.floor(Date.now() / 1000) + 3600;

function playerToken(roles: string[] = ['PLAYER']): string {
  return makeJwt({ sub: 'p-1001', sid: 's-2001', cur: 'EUR', exp: futureExp, roles });
}

const server = setupServer(...handlers);

function renderAt(path: string): void {
  const qc = createQueryClient();
  render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[path]}>
        <App />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('App routing', () => {
  beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
  afterEach(() => server.resetHandlers());
  afterAll(() => server.close());

  beforeEach(() => {
    useAuthStore.getState().clear();
    useSessionStore.getState().reset();
  });

  it('redirects unauthenticated users away from /play to /auth (but /auth is 404 without dev-token)', async () => {
    renderAt('/play');
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /404/i })).toBeInTheDocument();
    });
  });

  it('renders the FSM mirror on /play after a successful init', async () => {
    useAuthStore.getState().setToken(playerToken());
    renderAt('/play');

    await waitFor(() => {
      expect(screen.getByText('FREE_SPINS_AWAITING')).toBeInTheDocument();
    });
    expect(screen.getByText('START_FREE_SPINS')).toBeInTheDocument();
  });

  it('redirects a non-ADMIN user away from /admin to /play', async () => {
    useAuthStore.getState().setToken(playerToken(['PLAYER']));
    renderAt('/admin');

    await waitFor(() => {
      expect(screen.queryByRole('heading', { name: /admin tools/i })).not.toBeInTheDocument();
    });
    await waitFor(() => {
      expect(screen.getByText('FREE_SPINS_AWAITING')).toBeInTheDocument();
    });
  });

  it('renders /admin for users with the ADMIN role', async () => {
    useAuthStore.getState().setToken(playerToken(['PLAYER', 'ADMIN']));
    renderAt('/admin');
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /admin tools/i })).toBeInTheDocument();
    });
  });

  it('on SESSION_VERSION_CONFLICT clears the store, shows a toast, and refetches /init', async () => {
    let initCalls = 0;
    server.use(
      mswHttp.post('*/api/v1/slot/init', () => {
        initCalls += 1;
        return HttpResponse.json({
          sessionId: 's-2001',
          sessionVersion: 7,
          gameId: 'aztec-fire',
          mathVersion: 'v1',
          currency: 'EUR',
          balance: 98.5,
          currentState: 'BASE_GAME',
          remainingFreeSpins: 0,
          accumulatedFreeSpinsWin: 0,
          currentBet: 1,
          availableActions: ['SPIN'],
          featureFlags: { bonusBuyEnabled: true, powerBetEnabled: true },
          activeFeatureView: null,
        });
      }),
    );

    useAuthStore.getState().setToken(playerToken());
    renderAt('/play');

    await waitFor(() => {
      expect(initCalls).toBe(1);
      expect(screen.getByText('BASE_GAME')).toBeInTheDocument();
    });

    notifyRgsError(
      new RgsHttpError({
        code: 'SESSION_VERSION_CONFLICT',
        message: 'stale version',
        httpStatus: 409,
        traceId: 'trace-abc',
        timestamp: new Date().toISOString(),
      }),
    );

    await waitFor(() => expect(initCalls).toBe(2));
    await waitFor(() => {
      expect(screen.getByText(/session refreshed/i)).toBeInTheDocument();
    });
  });
});
