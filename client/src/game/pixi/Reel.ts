import { Container, Sprite, Texture } from 'pixi.js';

import { GRID } from '@/game/math/aztec-fire';

/**
 * A single reel column. Owns `GRID.rows` sprites stacked vertically; the
 * canvas itself is dumb (Q7) — `setSymbols(symbolIds)` is the only behaviour.
 *
 * The reel is anchor-centered so its parent (`SlotGrid`) can lay it out by
 * column origin rather than worrying about per-cell math.
 */
export interface ReelOptions {
  /** Width of one cell in CSS px before resolution scaling. */
  cellSize: number;
  /** Textures keyed by `symbolId`. Missing ids fall back to `Texture.EMPTY`. */
  textures: ReadonlyMap<number, Texture>;
}

export class Reel extends Container {
  readonly cellSize: number;
  private readonly slots: Sprite[];
  private readonly textures: ReadonlyMap<number, Texture>;

  constructor(options: ReelOptions) {
    super();
    this.cellSize = options.cellSize;
    this.textures = options.textures;
    this.slots = [];

    for (let row = 0; row < GRID.rows; row++) {
      const sprite = new Sprite();
      sprite.anchor.set(0.5);
      sprite.width = options.cellSize;
      sprite.height = options.cellSize;
      sprite.x = options.cellSize / 2;
      sprite.y = options.cellSize / 2 + row * options.cellSize;
      this.addChild(sprite);
      this.slots.push(sprite);
    }
  }

  setSymbols(symbolIds: readonly number[]): void {
    if (symbolIds.length !== GRID.rows) {
      throw new Error(
        `Reel.setSymbols expected ${GRID.rows} symbols, got ${symbolIds.length}`,
      );
    }
    for (let row = 0; row < GRID.rows; row++) {
      const id = symbolIds[row];
      const slot = this.slots[row];
      if (slot === undefined || id === undefined) continue;
      slot.texture = this.textures.get(id) ?? Texture.EMPTY;
    }
  }

  /** Test-only: lets tests assert how many cells exist. */
  get cellCount(): number {
    return this.slots.length;
  }
}
