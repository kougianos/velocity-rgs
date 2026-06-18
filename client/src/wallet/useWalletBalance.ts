import { useIsMutating, useQuery, type UseQueryResult } from '@tanstack/react-query';
import { useEffect } from 'react';

import { balance, type WalletBalanceResponse } from '@/api/wallet/balance';
import { selectIsAuthenticated, useAuthStore } from '@/auth/authStore';
import { env } from '@/env';
import { useSessionStore } from '@/session/sessionStore';

import { useWalletStore } from './walletStore';

/**
 * Periodic `/api/v1/wallet/balance` refetch. The interval is paused while any
 * RGS mutation (spin, feature/*, wallet/*) is in flight to avoid racing the
 * server's authoritative settlement. Successful spin/feature responses bump
 * `sessionVersion`; the effect below also forces a refetch on that signal so
 * the balance reconciles after every settlement.
 */
export function useWalletBalance(): UseQueryResult<WalletBalanceResponse, Error> {
  const authed = useAuthStore(selectIsAuthenticated);
  const playerId = useAuthStore((s) => s.playerId);
  const inFlight = useIsMutating();
  const applyBalance = useWalletStore((s) => s.applyBalance);
  const sessionVersion = useSessionStore((s) => s.sessionVersion);

  const query = useQuery({
    queryKey: ['wallet', 'balance', playerId],
    queryFn: () => balance(),
    enabled: authed,
    staleTime: 0,
    gcTime: 5 * 60 * 1000,
    refetchInterval: inFlight > 0 ? false : env.VITE_WALLET_REFRESH_MS,
    refetchOnWindowFocus: false,
  });

  useEffect(() => {
    if (query.data) applyBalance(query.data);
  }, [query.data, applyBalance]);

  useEffect(() => {
    if (sessionVersion !== null && authed) {
      void query.refetch();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sessionVersion]);

  return query;
}
