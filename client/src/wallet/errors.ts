import { useEffect } from 'react';

import { RgsHttpError } from '@/api/http/errors';
import { logger } from '@/observability/logger';
import { subscribeRgsError } from '@/session/errorBus';
import { pushToast } from '@/ui/toast/toastStore';

/**
 * Wallet-domain error UX dispatcher per Appendix D.
 *
 * - INSUFFICIENT_FUNDS → toast: "Not enough balance for this action."
 * - CURRENCY_MISMATCH  → modal-style toast asking the player to reload.
 * - DUPLICATE_TRANSACTION → developer bug; logged at ERROR, never user-facing.
 *
 * Other codes (SESSION_VERSION_CONFLICT, AUTH_FAILED, …) are owned by
 * {@link useSessionRecovery}; the error bus delivers each error to every
 * subscriber so the two handlers compose without ordering hazards.
 */
export function useWalletErrorRecovery(): void {
  useEffect(() => {
    const handler = (err: unknown): void => {
      if (!(err instanceof RgsHttpError)) return;
      switch (err.code) {
        case 'INSUFFICIENT_FUNDS':
          pushToast('info', 'Not enough balance for this action.', { traceId: err.traceId });
          return;
        case 'CURRENCY_MISMATCH':
          pushToast('error', 'Currency mismatch. Please reload the game.', {
            traceId: err.traceId,
            ttlMs: 8000,
          });
          return;
        case 'DUPLICATE_TRANSACTION':
          // client bug: silent for the user, loud for ops.
          logger.error('[wallet] DUPLICATE_TRANSACTION', { traceId: err.traceId });
          return;
        default:
          return;
      }
    };
    return subscribeRgsError(handler);
  }, []);
}
