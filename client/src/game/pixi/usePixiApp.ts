import { useEffect, useRef, useState } from 'react';

import { PixiApp, type PixiAppInitOptions } from './PixiApp';

const HOST_ID = 'pixi-host';

export type UsePixiAppStatus = 'idle' | 'initialising' | 'ready' | 'error';

export interface UsePixiAppResult {
  app: PixiApp | null;
  status: UsePixiAppStatus;
  error: Error | null;
}

/**
 * Mounts a {@link PixiApp} into the global `<canvas id="pixi-host">` element
 * (declared in `index.html`) on mount, and destroys it on unmount.
 *
 * Idempotent under React StrictMode double-invoke: if a previous mount left
 * an alive instance attached to the same canvas it is destroyed first.
 */
export function usePixiApp(
  options: Omit<PixiAppInitOptions, 'canvas'>,
): UsePixiAppResult {
  const appRef = useRef<PixiApp | null>(null);
  const [status, setStatus] = useState<UsePixiAppStatus>('idle');
  const [error, setError] = useState<Error | null>(null);

  // Capture options once: we don't want every re-render to re-init Pixi. The
  // first render's options win; resize / re-init must be explicit.
  const optionsRef = useRef(options);

  useEffect(() => {
    let cancelled = false;
    const canvas = document.getElementById(HOST_ID);
    if (!(canvas instanceof HTMLCanvasElement)) {
      const err = new Error(`#${HOST_ID} canvas not found`);
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
