import { useEffect, useMemo, useRef, useState } from 'react';

import { useSessionStore } from '@/session/sessionStore';

import { loadSymbolTextures } from './assets';
import { SlotGrid } from './SlotGrid';
import styles from './SlotStage.module.css';
import { usePixiApp } from './usePixiApp';

const CELL_SIZE = 128;
const REEL_GAP = 8;

/**
 * Static fixture (Appendix A.2) used while the session has no spin response
 * yet. Once the first real spin lands, `sessionStore.lastSpin.matrix` takes
 * over.
 */
const PLACEHOLDER_MATRIX: readonly (readonly number[])[] = [
  [2, 5, 1, 8, 9],
  [3, 12, 1, 1, 4],
  [7, 8, 2, 3, 11],
];

export interface SlotStageProps {
  initialMatrix?: readonly (readonly number[])[];
}

/**
 * Pixi host React component. Owns:
 *   - the {@link PixiApp} lifecycle via {@link usePixiApp},
 *   - the {@link SlotGrid} child container,
 *   - the matrix subscription to `sessionStore.lastSpin`.
 *
 * Does NOT decide outcomes (Q1). The matrix is rendered verbatim from the
 * server's spin response (or the placeholder fixture before the first spin).
 */
export function SlotStage({ initialMatrix = PLACEHOLDER_MATRIX }: SlotStageProps): JSX.Element {
  const { app, status, error } = usePixiApp({
    width: typeof window === 'undefined' ? 800 : window.innerWidth,
    height: typeof window === 'undefined' ? 480 : window.innerHeight,
  });
  const [texturesLoaded, setTexturesLoaded] = useState(false);
  const [textureError, setTextureError] = useState<Error | null>(null);
  const gridRef = useRef<SlotGrid | null>(null);

  const lastSpinMatrix = useSessionStore((s) => s.lastSpin?.matrix);
  const matrix = useMemo<readonly (readonly number[])[]>(
    () => lastSpinMatrix ?? initialMatrix,
    [lastSpinMatrix, initialMatrix],
  );

  // Once Pixi is ready and textures are loaded, build the grid and attach.
  useEffect(() => {
    if (status !== 'ready' || !app) return;
    let cancelled = false;
    loadSymbolTextures()
      .then((textures) => {
        if (cancelled) return;
        const grid = new SlotGrid({ cellSize: CELL_SIZE, reelGap: REEL_GAP, textures });
        gridRef.current = grid;
        app.app.stage.addChild(grid);
        centerGrid(grid, app.app.renderer.width, app.app.renderer.height);
        grid.renderMatrix(matrix);
        setTexturesLoaded(true);
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        setTextureError(e instanceof Error ? e : new Error(String(e)));
      });

    return () => {
      cancelled = true;
      const grid = gridRef.current;
      if (grid) {
        if (app.isInitialised) app.app.stage.removeChild(grid);
        grid.destroy({ children: true });
        gridRef.current = null;
      }
      setTexturesLoaded(false);
    };
    // We deliberately ignore `matrix` here — re-render below handles updates.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [status, app]);

  // Re-render the matrix whenever it changes after first paint.
  useEffect(() => {
    if (!texturesLoaded) return;
    gridRef.current?.renderMatrix(matrix);
  }, [matrix, texturesLoaded]);

  // Keep the grid centered on viewport resize.
  useEffect(() => {
    if (status !== 'ready' || !app) return;
    const handle = (): void => {
      app.resize(window.innerWidth, window.innerHeight);
      if (gridRef.current) {
        centerGrid(gridRef.current, app.app.renderer.width, app.app.renderer.height);
      }
    };
    window.addEventListener('resize', handle);
    return () => window.removeEventListener('resize', handle);
  }, [status, app]);

  return (
    <section className={styles.stage} aria-label="Slot reels" aria-hidden="true">
      {status === 'initialising' && <div className={styles.status}>Loading reels…</div>}
      {status === 'ready' && !texturesLoaded && !textureError && (
        <div className={styles.status}>Loading symbols…</div>
      )}
      {(status === 'error' || textureError) && (
        <div className={styles.error}>
          {error?.message ?? textureError?.message ?? 'Reels unavailable'}
        </div>
      )}
    </section>
  );
}

function centerGrid(grid: SlotGrid, viewportWidth: number, viewportHeight: number): void {
  grid.x = (viewportWidth - grid.pixelWidth) / 2;
  grid.y = (viewportHeight - grid.pixelHeight) / 2;
}
