import { Container, Graphics } from 'pixi.js';
import { describe, expect, it, vi } from 'vitest';

import type { ActiveFeatureView } from '@/session/sessionStore';

import { PickBoard } from './PickBoard';

function makeView(opts: Partial<ActiveFeatureView> & { boardSize: number }): ActiveFeatureView {
  return {
    boardSize: opts.boardSize,
    openedPositions: opts.openedPositions ?? [],
    revealedPicks: opts.revealedPicks ?? [],
    currentCollected: opts.currentCollected ?? 0,
    totalFeatureWin: opts.totalFeatureWin ?? 0,
    remainingPicks: opts.remainingPicks ?? opts.boardSize,
    status: opts.status ?? 'IN_PROGRESS',
  };
}

describe('PickBoard', () => {
  it('creates exactly boardSize tile children', () => {
    const board = new PickBoard({ boardSize: 12, tileSize: 64, onPick: () => {} });
    expect(board.tileCount).toBe(12);
    expect(board.children).toHaveLength(12);
    for (const child of board.children) {
      expect(child).toBeInstanceOf(Container);
    }
  });

  it('lays tiles out in a roughly-square grid (12 → 4×3)', () => {
    const board = new PickBoard({ boardSize: 12, tileSize: 50, tileGap: 10, onPick: () => {} });
    expect(board.cols).toBe(4);
    expect(board.rows).toBe(3);
    expect(board.pixelWidth).toBe(4 * 50 + 3 * 10);
    expect(board.pixelHeight).toBe(3 * 50 + 2 * 10);
  });

  it('emits onPick for unopened tiles only', () => {
    const onPick = vi.fn();
    const board = new PickBoard({ boardSize: 6, tileSize: 50, onPick });
    board.setState(
      makeView({ boardSize: 6, openedPositions: [0, 2], revealedPicks: [] }),
    );

    // Simulate pointertap on tile 0 (opened) — should not fire
    board.children[0]!.emit('pointertap', {} as never);
    expect(onPick).not.toHaveBeenCalled();

    // Simulate pointertap on tile 1 (unopened) — should fire
    board.children[1]!.emit('pointertap', {} as never);
    expect(onPick).toHaveBeenCalledWith(1);
  });

  it('marks opened tiles as non-interactive after setState', () => {
    const board = new PickBoard({ boardSize: 4, tileSize: 50, onPick: () => {} });
    board.setState(
      makeView({ boardSize: 4, openedPositions: [3], revealedPicks: [] }),
    );
    expect((board.children[3] as Container).eventMode).toBe('none');
    expect((board.children[0] as Container).eventMode).toBe('static');
    expect(board.openedTileCount).toBe(1);
  });

  it('paints revealed picks with the matching tile type', () => {
    const board = new PickBoard({ boardSize: 4, tileSize: 50, onPick: () => {} });
    board.setState(
      makeView({
        boardSize: 4,
        openedPositions: [0, 1, 2],
        revealedPicks: [
          { position: 0, tileType: 'CREDITS', value: 5 },
          { position: 1, tileType: 'MULTIPLIER', value: 3 },
          { position: 2, tileType: 'BLANK', value: 0 },
        ],
      }),
    );
    // We can't introspect Graphics fill color directly in jsdom; assert the
    // bg child exists and the tile is non-interactive — i.e. paintRevealed
    // ran.
    const opened = board.children.slice(0, 3) as Container[];
    for (const tile of opened) {
      expect(tile.eventMode).toBe('none');
      const bg = tile.children[0];
      expect(bg).toBeInstanceOf(Graphics);
    }
  });

  it('resets all tiles to hidden on a null view', () => {
    const board = new PickBoard({ boardSize: 4, tileSize: 50, onPick: () => {} });
    board.setState(
      makeView({ boardSize: 4, openedPositions: [0], revealedPicks: [] }),
    );
    expect(board.openedTileCount).toBe(1);
    board.setState(null);
    expect(board.openedTileCount).toBe(0);
    for (const tile of board.children) {
      expect((tile as Container).eventMode).toBe('static');
    }
  });
});
