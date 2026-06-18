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

/**
 * Mounts a {@link PixiApp} into a `<canvas>` element (the global
 * `#pixi-host` by default) on mount, and destroys it on unmount.
 *
 * Idempotent under React StrictMode double-invoke: if a previous mount left
 * an alive instance attached to the same canvas it is destroyed first.
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

    const next = new PixiApp();
    setStatus('initialising');
    setError(null);

    next
      .init({ ...optionsRef.current, canvas })
      .then(() => {
        if (cancelled) {
          next.destroy();
          return;
        }
        appRef.current = next;
        setStatus('ready');
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        const err = e instanceof Error ? e : new Error(String(e));
        setError(err);
        setStatus('error');
      });

    return () => {
      cancelled = true;
      if (appRef.current === next) {
        appRef.current = null;
      }
      next.destroy();
    };
  }, []);

  return { app: appRef.current, status, error };
}
