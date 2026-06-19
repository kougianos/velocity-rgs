import { useEffect, useRef, useState } from 'react';

import { PixiApp, type PixiAppInitOptions } from './PixiApp';

const DEFAULT_HOST_ID = 'pixi-host';

export type UsePixiAppStatus = 'idle' | 'initialising' | 'ready' | 'error';

export interface UsePixiAppResult {
  app: PixiApp | null;
  status: UsePixiAppStatus;
  error: Error | null;
}

export interface UsePixiAppOptions extends Omit<PixiAppInitOptions, 'canvas'> {
  /** Existing canvas element id to bind to. Defaults to `pixi-host`. */
  canvasId?: string;
}

interface SharedSlot {
  app: PixiApp;
  initPromise: Promise<void>;
  refCount: number;
  destroyTimer: ReturnType<typeof setTimeout> | null;
}

// Shared per HTMLCanvasElement so React StrictMode's unmount → remount cycle
// re-acquires the live instance instead of churning the WebGL context. Pixi v8
// cannot reliably re-init on a canvas whose context was already destroyed
// (symptom: "Cannot read properties of null (reading 'split')").
const sharedSlots = new Map<HTMLCanvasElement, SharedSlot>();
const STRICT_MODE_REUSE_WINDOW_MS = 100;

/**
 * Mounts a {@link PixiApp} into a `<canvas>` element (the global
 * `#pixi-host` by default) on mount, and destroys it on unmount.
 *
 * Idempotent under React StrictMode double-invoke: instances are shared per
 * canvas via a module-level slot map and destroy is deferred briefly so an
 * immediate remount can reclaim the live instance.
 */
export function usePixiApp(options: UsePixiAppOptions): UsePixiAppResult {
  const appRef = useRef<PixiApp | null>(null);
  const [status, setStatus] = useState<UsePixiAppStatus>('idle');
  const [error, setError] = useState<Error | null>(null);

  // Capture options once: we don't want every re-render to re-init Pixi. The
  // first render's options win; resize / re-init must be explicit.
  const optionsRef = useRef(options);
  const canvasIdRef = useRef(options.canvasId ?? DEFAULT_HOST_ID);

  useEffect(() => {
    let cancelled = false;
    const id = canvasIdRef.current;
    const canvas = document.getElementById(id);
    if (!(canvas instanceof HTMLCanvasElement)) {
      const err = new Error(`#${id} canvas not found`);
      setError(err);
      setStatus('error');
      return;
    }

    let slot = sharedSlots.get(canvas);
    if (slot) {
      slot.refCount += 1;
      if (slot.destroyTimer) {
        clearTimeout(slot.destroyTimer);
        slot.destroyTimer = null;
      }
    } else {
      const fresh = new PixiApp();
      const initPromise = fresh.init({ ...optionsRef.current, canvas });
      slot = { app: fresh, initPromise, refCount: 1, destroyTimer: null };
      sharedSlots.set(canvas, slot);
    }
    const localSlot = slot;

    if (localSlot.app.isInitialised) {
      appRef.current = localSlot.app;
      setStatus('ready');
      setError(null);
    } else {
      setStatus('initialising');
      setError(null);
      localSlot.initPromise
        .then(() => {
          if (cancelled) return;
          appRef.current = localSlot.app;
          setStatus('ready');
        })
        .catch((e: unknown) => {
          if (cancelled) return;
          const err = e instanceof Error ? e : new Error(String(e));
          setError(err);
          setStatus('error');
        });
    }

    return () => {
      cancelled = true;
      if (appRef.current === localSlot.app) {
        appRef.current = null;
      }
      localSlot.refCount -= 1;
      if (localSlot.refCount === 0) {
        localSlot.destroyTimer = setTimeout(() => {
          if (sharedSlots.get(canvas) === localSlot) {
            sharedSlots.delete(canvas);
          }
          localSlot.destroyTimer = null;
          localSlot.app.destroy();
        }, STRICT_MODE_REUSE_WINDOW_MS);
      }
    };
  }, []);

  return { app: appRef.current, status, error };
}
