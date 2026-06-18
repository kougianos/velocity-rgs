import { useEffect } from 'react';

import { useAuthStore } from '@/auth/authStore';
import { useSessionStore } from '@/session/sessionStore';

import { registerLogContext, type LogContext } from './logger';

/**
 * Mount-once bridge that feeds {@link useAuthStore} and {@link useSessionStore}
 * snapshots into the structured logger. Every log emitted while this hook is
 * mounted carries the live `playerId`, `sessionId`, `gameId`, and `roundId`.
 */
export function useLoggerContext(): void {
  useEffect(() => {
    const provider = (): LogContext => {
      const auth = useAuthStore.getState();
      const session = useSessionStore.getState();
      const ctx: LogContext = {};
      if (auth.playerId) ctx.playerId = auth.playerId;
      if (session.sessionId ?? auth.sessionId) {
        ctx.sessionId = session.sessionId ?? auth.sessionId ?? undefined;
      }
      if (session.gameId) ctx.gameId = session.gameId;
      if (session.lastSpin?.roundId) ctx.roundId = session.lastSpin.roundId;
      return ctx;
    };
    return registerLogContext(provider);
  }, []);
}
