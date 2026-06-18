/**
 * Module-level RGS error dispatcher. The {@link createQueryClient} hooks
 * (QueryCache.onError, MutationCache.onError) call {@link notifyRgsError} for
 * every failed query/mutation. Multiple hooks may subscribe; each receives
 * the error in registration order. See {@link useSessionRecovery} and
 * {@link useWalletErrorRecovery} for canonical consumers.
 */
type Handler = (error: unknown) => void;

const handlers = new Set<Handler>();

export function subscribeRgsError(handler: Handler): () => void {
  handlers.add(handler);
  return () => {
    handlers.delete(handler);
  };
}

/**
 * Back-compat single-handler setter. Passing `null` clears all handlers; this
 * matches the previous semantics used by `useSessionRecovery`.
 */
export function setRgsErrorHandler(handler: Handler | null): void {
  handlers.clear();
  if (handler) handlers.add(handler);
}

export function notifyRgsError(error: unknown): void {
  for (const h of handlers) h(error);
}
