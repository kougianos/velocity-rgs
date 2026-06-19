import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';

import { logger } from '@/observability/logger';
import { useSessionStore } from '@/session/sessionStore';

import { loadSymbolTextures } from './assets';
import { SlotGrid } from './SlotGrid';
import styles from './SlotStage.module.css';
import { SpinAnimator } from './SpinAnimator';
import { usePixiApp } from './usePixiApp';

const CANVAS_ID = 'pixi-slot-host';
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

interface StageSize {
  readonly width: number;
  readonly height: number;
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
  const stageRef = useRef<HTMLElement>(null);
  const [size, setSize] = useState<StageSize | null>(null);

  // Measure the stage element synchronously before paint and follow resizes
  // so Pixi's internal resolution stays in sync with the CSS-driven layout.
  useLayoutEffect(() => {
    const el = stageRef.current;
    if (!el) return;
    const measure = (): void => {
      const rect = el.getBoundingClientRect();
      const w = Math.max(1, Math.round(rect.width));
      const h = Math.max(1, Math.round(rect.height));
      setSize((prev) =>
        prev && prev.width === w && prev.height === h ? prev : { width: w, height: h },
      );
    };
    measure();
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  return (
    <section ref={stageRef} className={styles.stage} aria-label="Slot reels" aria-hidden="true">
      <canvas id={CANVAS_ID} className={styles.canvas} aria-hidden="true" />
      {size ? (
        <SlotStageRenderer
          width={size.width}
          height={size.height}
          initialMatrix={initialMatrix}
        />
      ) : (
        <div className={styles.status}>Loading reels…</div>
      )}
    </section>
  );
}

interface SlotStageRendererProps {
  readonly width: number;
  readonly height: number;
  readonly initialMatrix: readonly (readonly number[])[];
}

function SlotStageRenderer({
  width,
  height,
  initialMatrix,
}: SlotStageRendererProps): JSX.Element | null {
  const { app, status, error } = usePixiApp({
    canvasId: CANVAS_ID,
    width,
    height,
  });
  const [texturesLoaded, setTexturesLoaded] = useState(false);
  const [textureError, setTextureError] = useState<Error | null>(null);
  const gridRef = useRef<SlotGrid | null>(null);
  const animatorRef = useRef<SpinAnimator | null>(null);
  const animatingRef = useRef<Promise<void> | null>(null);

  const lastSpin = useSessionStore((s) => s.lastSpin);
  const matrix = useMemo<readonly (readonly number[])[]>(
    () => lastSpin?.matrix ?? initialMatrix,
    [lastSpin, initialMatrix],
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
        animatorRef.current = new SpinAnimator(grid, {
          cellSize: CELL_SIZE,
          reelGap: REEL_GAP,
        });
        setTexturesLoaded(true);
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        setTextureError(e instanceof Error ? e : new Error(String(e)));
      });

    return () => {
      cancelled = true;
      const grid = gridRef.current;
      animatorRef.current?.destroy();
      animatorRef.current = null;
      if (grid) {
        if (app.isInitialised) app.app.stage.removeChild(grid);
        grid.destroy({ children: true });
        gridRef.current = null;
      }
      setTexturesLoaded(false);
    };
    // We deliberately ignore `matrix` here — animator below handles updates.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [status, app]);

  // Play the spin animator whenever a fresh `SpinResponse` lands. Earlier
  // responses are ignored (the session store already drops stale versions).
  useEffect(() => {
    if (!texturesLoaded) return;
    const animator = animatorRef.current;
    const grid = gridRef.current;
    if (!animator || !grid) return;
    if (!lastSpin) {
      grid.renderMatrix(matrix);
      return;
    }
    // Serialise plays so two rapid-fire responses don't overlap visually.
    const prior = animatingRef.current ?? Promise.resolve();
    const next = prior.then(() => animator.play(lastSpin));
    animatingRef.current = next;
    void next.catch((e: unknown) => {
      logger.error('[SlotStage] spin animation failed', {
        error: e instanceof Error ? e.message : String(e),
      });
    });
  }, [lastSpin, texturesLoaded, matrix]);

  // Resize the renderer and re-center the grid when the stage element changes.
  useEffect(() => {
    if (status !== 'ready' || !app) return;
    app.resize(width, height);
    if (gridRef.current) {
      centerGrid(gridRef.current, app.app.renderer.width, app.app.renderer.height);
    }
  }, [status, app, width, height]);

  return (
    <>
      {status === 'initialising' && <div className={styles.status}>Loading reels…</div>}
      {status === 'ready' && !texturesLoaded && !textureError && (
        <div className={styles.status}>Loading symbols…</div>
      )}
      {(status === 'error' || textureError) && (
        <div className={styles.error}>
          {error?.message ?? textureError?.message ?? 'Reels unavailable'}
        </div>
      )}
    </>
  );
}

function centerGrid(grid: SlotGrid, viewportWidth: number, viewportHeight: number): void {
  grid.x = (viewportWidth - grid.pixelWidth) / 2;
  grid.y = (viewportHeight - grid.pixelHeight) / 2;
}
