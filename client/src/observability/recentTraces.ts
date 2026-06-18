/**
 * Tiny in-memory ring buffer of trace IDs surfaced to the player (or logged
 * by mutations). Consumed by the DebugHud (gated on `VITE_ENABLE_DEBUG_HUD`)
 * and safe to call from anywhere — never throws, never has side effects when
 * the HUD is disabled (the array just holds a few strings).
 */

export interface RecentTrace {
  readonly id: string;
  readonly source: string;
  readonly traceId: string;
  readonly at: string;
}

const MAX_RECENT = 8;
const recent: RecentTrace[] = [];
const listeners = new Set<(snap: readonly RecentTrace[]) => void>();
let counter = 0;

export function recordTrace(source: string, traceId: string | undefined): void {
  if (!traceId) return;
  counter += 1;
  recent.unshift({
    id: `${Date.now()}-${counter}`,
    source,
    traceId,
    at: new Date().toISOString().slice(11, 19),
  });
  if (recent.length > MAX_RECENT) recent.length = MAX_RECENT;
  const snap = recent.slice();
  listeners.forEach((l) => l(snap));
}

export function subscribeRecentTraces(
  listener: (snap: readonly RecentTrace[]) => void,
): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function getRecentTraces(): readonly RecentTrace[] {
  return recent.slice();
}

/** Test-only. */
export function _resetRecentTraces(): void {
  recent.length = 0;
  counter = 0;
}
