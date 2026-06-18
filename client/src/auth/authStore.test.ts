import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { getAuthToken } from '@/api/http/authToken';

import { selectHasRole, selectIsAuthenticated, useAuthStore } from './authStore';

function encodeBase64Url(input: string): string {
  return btoa(input).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function makeJwt(payload: Record<string, unknown>): string {
  const header = encodeBase64Url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const body = encodeBase64Url(JSON.stringify(payload));
  return `${header}.${body}.sig`;
}

const futureExp = Math.floor(Date.now() / 1000) + 3600;
const pastExp = Math.floor(Date.now() / 1000) - 3600;

describe('authStore', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
  });

  afterEach(() => {
    useAuthStore.getState().clear();
  });

  it('round-trips a freshly minted token through the store', () => {
    const token = makeJwt({
      sub: 'p-1001',
      sid: 's-2001',
      cur: 'EUR',
      exp: futureExp,
      roles: ['PLAYER', 'ADMIN'],
    });

    useAuthStore.getState().setToken(token);
    const state = useAuthStore.getState();

    expect(state.token).toBe(token);
    expect(state.playerId).toBe('p-1001');
    expect(state.sessionId).toBe('s-2001');
    expect(state.currency).toBe('EUR');
    expect(state.roles).toEqual(['PLAYER', 'ADMIN']);
    expect(state.expiresAt?.getTime()).toBe(futureExp * 1000);

    expect(getAuthToken()).toBe(token);
    expect(selectIsAuthenticated(state)).toBe(true);
    expect(selectHasRole('ADMIN')(state)).toBe(true);
    expect(selectHasRole('SUPER')(state)).toBe(false);
  });

  it('clear() empties the store and the axios token holder', () => {
    const token = makeJwt({ sub: 'p', sid: 's', cur: 'EUR', exp: futureExp, roles: ['PLAYER'] });
    useAuthStore.getState().setToken(token);

    useAuthStore.getState().clear();
    const state = useAuthStore.getState();

    expect(state.token).toBeNull();
    expect(state.playerId).toBeNull();
    expect(state.roles).toEqual([]);
    expect(getAuthToken()).toBeNull();
    expect(selectIsAuthenticated(state)).toBe(false);
  });

  it('selectIsAuthenticated is false for expired tokens', () => {
    const token = makeJwt({ sub: 'p', sid: 's', cur: 'EUR', exp: pastExp, roles: [] });
    useAuthStore.getState().setToken(token);
    expect(selectIsAuthenticated(useAuthStore.getState())).toBe(false);
  });
});
