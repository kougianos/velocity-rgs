import { useQuery, type UseQueryResult } from '@tanstack/react-query';
import { useEffect } from 'react';

import { RgsHttpError } from '@/api/http/errors';
import { init, type SlotInitResponse } from '@/api/slot/init';
import { selectIsAuthenticated, useAuthStore } from '@/auth/authStore';
import { env } from '@/env';

import { useSessionStore } from './sessionStore';

/**
 * Single-shot `/api/v1/slot/init` query keyed by (gameId, currency). The
 * server is authoritative for every field; the hook pipes the response into
 * the read-only session mirror. Reruns are deliberate: only via React Query
 * cache invalidation (see `useSessionRecovery`).
 *
 * Per Task 2.4: on `SESSION_NOT_FOUND` the query retries exactly once.
 */
export function useSessionInit(): UseQueryResult<SlotInitResponse, Error> {
  const authed = useAuthStore(selectIsAuthenticated);
  const applyInitResponse = useSessionStore((s) => s.applyInitResponse);

  const query = useQuery({
    queryKey: ['init', env.VITE_DEFAULT_GAME_ID, env.VITE_DEFAULT_CURRENCY],
    queryFn: () => init({ gameId: env.VITE_DEFAULT_GAME_ID, currency: env.VITE_DEFAULT_CURRENCY }),
    enabled: authed,
    staleTime: Infinity,
    gcTime: Infinity,
    retry: (failureCount, err) => {
      if (failureCount > 0) return false;
      return err instanceof RgsHttpError && err.code === 'SESSION_NOT_FOUND';
    },
  });

  useEffect(() => {
    if (query.data) {
      applyInitResponse(query.data);
    }
  }, [query.data, applyInitResponse]);

  return query;
}
