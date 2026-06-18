import { renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { useAuthStore } from '@/auth/authStore';
import { useSessionStore } from '@/session/sessionStore';

import { logger } from './logger';
import { useLoggerContext } from './useLoggerContext';

const consoleSpy = vi.spyOn(console, 'info').mockImplementation(() => {});

afterEach(() => {
  consoleSpy.mockClear();
  useAuthStore.getState().clear();
  useSessionStore.getState().reset();
});

describe('useLoggerContext', () => {
  it('feeds playerId, sessionId, gameId, and roundId into every log line', () => {
    useAuthStore.setState({
      token: 'tok',
      playerId: 'p-1001',
      sessionId: 's-2001',
      currency: 'EUR',
      roles: ['PLAYER'],
      expiresAt: new Date(Date.now() + 60_000),
    });
    useSessionStore.setState({
      sessionId: 's-2001',
      gameId: 'aztec-fire',
    });

    const { unmount } = renderHook(() => useLoggerContext());

    logger.info('ctx-test');
    expect(consoleSpy).toHaveBeenCalledOnce();
    const [, payload] = consoleSpy.mock.calls[0]!;
    expect(payload).toMatchObject({
      playerId: 'p-1001',
      sessionId: 's-2001',
      gameId: 'aztec-fire',
    });

    unmount();
    consoleSpy.mockClear();
    logger.info('after-unmount');
    const [, second] = consoleSpy.mock.calls[0]!;
    expect(second.playerId).toBeUndefined();
    expect(second.sessionId).toBeUndefined();
  });

  it('handles a session without lastSpin (no roundId)', () => {
    useAuthStore.setState({
      token: 'tok',
      playerId: 'p-1001',
      sessionId: null,
      currency: 'EUR',
      roles: ['PLAYER'],
      expiresAt: new Date(Date.now() + 60_000),
    });
    const { unmount } = renderHook(() => useLoggerContext());
    logger.info('no-round');
    const [, payload] = consoleSpy.mock.calls[0]!;
    expect(payload.roundId).toBeUndefined();
    unmount();
  });
});
