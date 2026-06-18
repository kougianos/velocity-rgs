import { useCallback, useEffect, useMemo, useRef, useState } from 'react';

import { Money, type Currency } from '@/common/money/Money';
import { usePixiApp } from '@/game/pixi/usePixiApp';
import { useSessionStore } from '@/session/sessionStore';

import { PickBoard } from './PickBoard';
import styles from './PickBoardScene.module.css';
import { useFeaturePick } from './useFeaturePick';

const CANVAS_ID = 'pixi-pickboard-host';
const TILE_SIZE = 96;
const TILE_GAP = 14;
const STAGE_PADDING = 24;

const TILE_LABELS: Record<string, string> = {
  CREDITS: 'Credits',
  MULTIPLIER: 'Multiplier',
  COLLECT: 'Collect',
  BLANK: 'Blank',
  END: 'End',
};

/**
 * Pick & Collect React host (Task 7.4). Owns a dedicated Pixi canvas, the
 * {@link PickBoard} child container, the click-to-pick wiring, and a small
 * status HUD (current collected / remaining picks).
 *
 * Returns `null` outside `PICK_COLLECT_LOOP` so it never crowds non-feature
 * states. The board view is rebuilt from `activeFeatureView` on every
 * server response (Q1, Q7).
 */
export function PickBoardScene(): JSX.Element | null {
  const currentState = useSessionStore((s) => s.currentState);
  const activeFeatureView = useSessionStore((s) => s.activeFeatureView);
  const sessionCurrency = useSessionStore((s) => s.currency);
  const lastPick = useSessionStore((s) => s.lastPick);

  if (currentState !== 'PICK_COLLECT_LOOP') {
    return null;
  }

  return (
    <PickBoardSceneActive
      view={activeFeatureView}
      currency={sessionCurrency ?? 'EUR'}
      lastPick={lastPick}
    />
  );
}

interface PickBoardSceneActiveProps {
  view: ReturnType<typeof useSessionStore.getState>['activeFeatureView'];
  currency: Currency;
  lastPick: ReturnType<typeof useSessionStore.getState>['lastPick'];
}

function PickBoardSceneActive({
  view,
  currency,
  lastPick,
}: PickBoardSceneActiveProps): JSX.Element {
  const mutation = useFeaturePick();
  const inflightRef = useRef(false);
  const boardRef = useRef<PickBoard | null>(null);
  const [boardReady, setBoardReady] = useState(false);

  const boardSize = view?.boardSize ?? 0;
  const stageWidth = useMemo(() => {
    const cols = Math.max(1, Math.ceil(Math.sqrt(boardSize)));
    return cols * TILE_SIZE + (cols - 1) * TILE_GAP + STAGE_PADDING * 2;
  }, [boardSize]);
  const stageHeight = useMemo(() => {
    const cols = Math.max(1, Math.ceil(Math.sqrt(boardSize)));
    const rows = Math.max(1, Math.ceil(boardSize / cols));
    return rows * TILE_SIZE + (rows - 1) * TILE_GAP + STAGE_PADDING * 2;
  }, [boardSize]);

  const { app, status, error } = usePixiApp({
    canvasId: CANVAS_ID,
    width: stageWidth,
    height: stageHeight,
    backgroundColor: 0x0d1117,
  });

  const handlePick = useCallback(
    (position: number): void => {
      if (inflightRef.current || mutation.isPending) return;
      inflightRef.current = true;
      mutation.mutate(
        { position },
        {
          onSettled: () => {
            inflightRef.current = false;
          },
        },
      );
    },
    [mutation],
  );

  // Build the PickBoard once we have a ready Pixi app and a known boardSize.
  useEffect(() => {
    if (status !== 'ready' || !app || boardSize <= 0) return;
    const board = new PickBoard({
      boardSize,
      tileSize: TILE_SIZE,
      tileGap: TILE_GAP,
      onPick: handlePick,
    });
    board.x = (app.app.renderer.width - board.pixelWidth) / 2;
    board.y = (app.app.renderer.height - board.pixelHeight) / 2;
    app.app.stage.addChild(board);
    boardRef.current = board;
    setBoardReady(true);

    return () => {
      if (app.isInitialised) app.app.stage.removeChild(board);
      board.destroy();
      boardRef.current = null;
      setBoardReady(false);
    };
  }, [status, app, boardSize, handlePick]);

  // Re-render the board on every server view update (e.g. after a pick).
  useEffect(() => {
    if (!boardReady) return;
    boardRef.current?.setState(view ?? null);
  }, [view, boardReady]);

  const remainingPicks = view?.remainingPicks ?? 0;
  const currentCollected = useMemo(() => {
    if (!view) return Money.zero(currency);
    return Money.fromNumber(view.currentCollected, currency);
  }, [view, currency]);

  const lastReveal = lastPick
    ? {
        type: TILE_LABELS[lastPick.resolvedTileType] ?? lastPick.resolvedTileType,
        value: lastPick.resolvedValue ?? null,
      }
    : null;

  return (
    <section className={styles.scene} aria-label="Pick and Collect board">
      <header className={styles.header}>
        <div className={styles.stat}>
          <span className={styles.statLabel}>Collected</span>
          <span className={styles.statValue}>{currentCollected.format(currency, 'en-US')}</span>
        </div>
        <div className={styles.stat}>
          <span className={styles.statLabel}>Picks left</span>
          <span className={styles.statValue}>{remainingPicks}</span>
        </div>
      </header>

      <div className={styles.canvasWrap} style={{ width: stageWidth, height: stageHeight }}>
        <canvas id={CANVAS_ID} className={styles.canvas} aria-hidden="true" />
        {status === 'initialising' && <div className={styles.status}>Loading board…</div>}
        {(status === 'error' || error) && (
          <div className={styles.error}>{error?.message ?? 'Board unavailable'}</div>
        )}
      </div>

      {lastReveal && (
        <p className={styles.reveal} role="status" aria-live="polite">
          Revealed: <strong>{lastReveal.type}</strong>
          {lastReveal.value !== null && <> ({lastReveal.value})</>}
        </p>
      )}
    </section>
  );
}
