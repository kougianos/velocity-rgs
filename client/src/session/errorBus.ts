/**
 * Module-level RGS error dispatcher. The {@link createQueryClient} hooks
 * (QueryCache.onError, MutationCache.onError) call {@link notifyRgsError} for
 * every failed query/mutation. The currently mounted {@link useSessionRecovery}
 * hook owns the active handler. Only one handler is registered at a time.
 */
type Handler = (error: unknown) => void;

let activeHandler: Handler | null = null;

export function setRgsErrorHandler(handler: Handler | null): void {
  activeHandler = handler;
}

export function notifyRgsError(error: unknown): void {
  activeHandler?.(error);
}
