import { Container, type Texture } from 'pixi.js';

import { GRID } from '@/game/math/aztec-fire';

import { Reel } from './Reel';

export interface SlotGridOptions {
  /** Side length of one cell, before resolution scaling. */
  cellSize: number;
  /** Gap between reels (px). */
  reelGap?: number;
  textures: ReadonlyMap<number, Texture>;
}

/**
 * Composition of `GRID.cols` reels arranged left-to-right. `renderMatrix`
 * accepts the canonical `matrix[row][col]` shape from `SpinResponse` and
 * dispatches each column to its reel.
 */
export class SlotGrid extends Container {
  readonly cellSize: number;
  readonly reelGap: number;
  private readonly reels: Reel[];

  constructor(options: SlotGridOptions) {
    super();
    this.cellSize = options.cellSize;
    this.reelGap = options.reelGap ?? 8;
    this.reels = [];

    for (let col = 0; col < GRID.cols; col++) {
      const reel = new Reel({ cellSize: options.cellSize, textures: options.textures });
      reel.x = col * (options.cellSize + this.reelGap);
      reel.y = 0;
      this.addChild(reel);
      this.reels.push(reel);
    }
  }

  /** Renders `matrix[row][col]` — server-canonical shape. */
  renderMatrix(matrix: readonly (readonly number[])[]): void {
    if (matrix.length !== GRID.rows) {
      throw new Error(
        `SlotGrid.renderMatrix expected ${GRID.rows} rows, got ${matrix.length}`,
      );
    }
    for (let col = 0; col < GRID.cols; col++) {
      const reel = this.reels[col];
      if (!reel) continue;
      const column: number[] = [];
      for (let row = 0; row < GRID.rows; row++) {
        const r = matrix[row];
        if (!r) {
          throw new Error(`SlotGrid.renderMatrix: missing row ${row}`);
        }
        const v = r[col];
        if (v === undefined) {
          throw new Error(`SlotGrid.renderMatrix: missing cell [${row}][${col}]`);
        }
        column.push(v);
      }
      reel.setSymbols(column);
    }
  }

  get pixelWidth(): number {
    return GRID.cols * this.cellSize + (GRID.cols - 1) * this.reelGap;
  }

  get pixelHeight(): number {
    return GRID.rows * this.cellSize;
  }

  /** Test-only: number of reel children. */
  get reelCount(): number {
    return this.reels.length;
  }
}
