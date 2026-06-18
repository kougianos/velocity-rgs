import { useMutation, type UseMutationResult } from '@tanstack/react-query';
import { useRef } from 'react';

import type { BonusBuyType } from '@/api/enums';
import { RgsNetworkError } from '@/api/http/errors';
import { featureBuy, type FeatureBuyResponse } from '@/api/slot/featureBuy';
import { newIdempotencyKey } from '@/common/idempotency/key';
import { useSessionStore } from '@/session/sessionStore';

export interface UseBuyFeatureInput {
  buyType: BonusBuyType;
  /** Bet size to base the buy cost on; usually the current ladder bet. */
  betSize: number;
}

export type UseBuyFeatureResult = UseMutationResult<
  FeatureBuyResponse,
  Error,
  UseBuyFeatureInput
>;

/**
 * Mutation hook wrapping `POST /api/v1/slot/feature/buy` (Task 7.2). Mirrors
 * the idempotency-key lifecycle from Appendix F: one key per user intent,
 * reused on transport failure, cleared on any definitive HTTP response.
 *
 * The cost shown to the player is a display-only product of `betSize ×
 * costMultiplier` (math mirror); the server's response `cost` is the
 * authoritative debit (Q1, Pitfall #9).
 */
export function useBuyFeature(): UseBuyFeatureResult {
  const keyRef = useRef<string | null>(null);
  const applyFeatureBuyResponse = useSessionStore((s) => s.applyFeatureBuyResponse);

  return useMutation<FeatureBuyResponse, Error, UseBuyFeatureInput>({
    mutationFn: async ({ buyType, betSize }) => {
      const { sessionId, sessionVersion, gameId } = useSessionStore.getState();
      if (sessionId === null || sessionVersion === null || gameId === null) {
        throw new Error('Session is not initialised; cannot buy a feature.');
      }
      if (keyRef.current === null) {
        keyRef.current = newIdempotencyKey();
      }
      return featureBuy(keyRef.current, {
        gameId,
        sessionId,
        sessionVersion,
        buyType,
        betSize,
      });
    },
    retry: 0,
    onSuccess: (resp) => {
      applyFeatureBuyResponse(resp);
      keyRef.current = null;
    },
    onError: (err) => {
      if (!(err instanceof RgsNetworkError)) {
        keyRef.current = null;
      }
    },
  });
}
