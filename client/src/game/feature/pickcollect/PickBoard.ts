import { Container, Graphics } from 'pixi.js';

import type { ActiveFeatureView } from '@/session/sessionStore';

/**
 * Pixi container that renders a Pick & Collect board (Task 7.3).
 *
 * The board is purely presentational: it draws `boardSize` tiles and
 * delegates the click event to {@link PickBoardOptions.onPick}. It never
 * decides outcomes (Q1, Q7) — tile reveal art is driven by the latest
 * {@link ActiveFeatureView} from the server.
 *
 * Hard guard (Task 7.8): tiles in `openedPositions` are not interactive
 * (`eventMode = 'none'`); the server is still authoritative — a stale view
 * would surface `ILLEGAL_STATE_TRANSITION` and trigger re-`init`.
 */
export interface PickBoardOptions {
  /** Number of tiles on the board (matches server `boardSize`). */
  boardSize: number;
  /** Side length of one tile, in px. */
  tileSize: number;
  /** Gap between tiles, in px. Defaults to 12. */
  tileGap?: number;
  /** Optional override for grid columns. Defaults to roughly √boardSize. */
  cols?: number;
  /** Click handler. Only fires for tiles not currently in `openedPositions`. */
  onPick: (position: number) => void;
}

/** Visual palette per tile state. */
const COLORS = {
  hiddenFill: 0x243044,
  hiddenStroke: 0x3a4a66,
  hiddenHoverFill: 0x2f3d57,
  CREDITS: 0x2ecc71,
  MULTIPLIER: 0xe67e22,
  COLLECT: 0x3498db,
  BLANK: 0x4a5562,
  END: 0xc0392b,
} as const;

type TileType = keyof typeof COLORS;

interface Tile {
  container: Container;
  bg: Graphics;
  position: number;
}

export class PickBoard extends Container {
  readonly boardSize: number;
  readonly tileSize: number;
  readonly tileGap: number;
  readonly cols: number;
  readonly rows: number;
  private readonly tiles: Tile[];
  private readonly onPick: (position: number) => void;
  private openedSet: Set<number> = new Set();

  constructor(options: PickBoardOptions) {
    super();
    this.boardSize = options.boardSize;
    this.tileSize = options.tileSize;
    this.tileGap = options.tileGap ?? 12;
    this.cols = options.cols ?? Math.max(1, Math.ceil(Math.sqrt(options.boardSize)));
    this.rows = Math.max(1, Math.ceil(options.boardSize / this.cols));
    this.onPick = options.onPick;
    this.tiles = [];

    for (let i = 0; i < this.boardSize; i++) {
      const row = Math.floor(i / this.cols);
      const col = i % this.cols;
      const tile = this.createTile(i);
      tile.container.x = col * (this.tileSize + this.tileGap);
      tile.container.y = row * (this.tileSize + this.tileGap);
      this.addChild(tile.container);
      this.tiles.push(tile);
    }
  }

  /** Total drawn width in px. */
  get pixelWidth(): number {
    return this.cols * this.tileSize + (this.cols - 1) * this.tileGap;
  }

  /** Total drawn height in px. */
  get pixelHeight(): number {
    return this.rows * this.tileSize + (this.rows - 1) * this.tileGap;
  }

  /** Test-only: tile child count. */
  get tileCount(): number {
    return this.tiles.length;
  }

  /**
   * Rerenders every tile based on the server's view. Opened tiles flip to
   * their resolved color; the rest stay hidden + interactive.
   */
  setState(view: ActiveFeatureView | null): void {
    if (!view) {
      this.openedSet = new Set();
      this.tiles.forEach((t) => this.paintHidden(t));
      return;
    }
    this.openedSet = new Set(view.openedPositions);
    const revealedByPos = new Map<number, TileType>();
    for (const rp of view.revealedPicks) {
      const ty = rp.tileType;
      if (this.isKnownTileType(ty)) revealedByPos.set(rp.position, ty);
    }

    for (const tile of this.tiles) {
      if (this.openedSet.has(tile.position)) {
        const ty = revealedByPos.get(tile.position) ?? 'BLANK';
        this.paintRevealed(tile, ty);
      } else {
        this.paintHidden(tile);
      }
    }
  }

  /** Test-only: number of opened tiles currently painted. */
  get openedTileCount(): number {
    return this.openedSet.size;
  }

  override destroy(): void {
    for (const tile of this.tiles) {
      tile.container.removeAllListeners();
    }
    super.destroy({ children: true });
  }

  // --- internals ---

  private createTile(position: number): Tile {
    const container = new Container();
    container.eventMode = 'static';
    container.cursor = 'pointer';
    const bg = new Graphics();
    container.addChild(bg);
    container.on('pointertap', () => {
      if (this.openedSet.has(position)) return;
      this.onPick(position);
    });
    const tile: Tile = { container, bg, position };
    this.paintHidden(tile);
    return tile;
  }

  private paintHidden(tile: Tile): void {
    tile.bg.clear();
    tile.bg
      .roundRect(0, 0, this.tileSize, this.tileSize, 10)
      .fill({ color: COLORS.hiddenFill })
      .stroke({ color: COLORS.hiddenStroke, width: 2, alignment: 1 });
    tile.container.eventMode = 'static';
    tile.container.cursor = 'pointer';
    tile.container.alpha = 1;
  }

  private paintRevealed(tile: Tile, type: TileType): void {
    const color = COLORS[type] ?? COLORS.BLANK;
    tile.bg.clear();
    tile.bg
      .roundRect(0, 0, this.tileSize, this.tileSize, 10)
      .fill({ color })
      .stroke({ color: 0xffffff, width: 2, alpha: 0.6, alignment: 1 });
    tile.container.eventMode = 'none';
    tile.container.cursor = 'default';
    tile.container.alpha = 0.92;
  }

  private isKnownTileType(value: string): value is TileType {
    return (
      value === 'CREDITS' ||
      value === 'MULTIPLIER' ||
      value === 'COLLECT' ||
      value === 'BLANK' ||
      value === 'END'
    );
  }
}
