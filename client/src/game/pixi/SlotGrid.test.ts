import { type Sprite, Texture } from 'pixi.js';
import { describe, expect, it } from 'vitest';

import { Reel } from './Reel';
import { SlotGrid } from './SlotGrid';

function makeTextureMap(ids: number[]): Map<number, Texture> {
  const map = new Map<number, Texture>();
  for (const id of ids) {
    map.set(id, new Texture());
  }
  return map;
}

// Canonical fixture (Appendix A.2).
const FIXTURE_MATRIX: readonly (readonly number[])[] = [
  [2, 5, 1, 8, 9],
  [3, 12, 1, 1, 4],
  [7, 8, 2, 3, 11],
];

describe('SlotGrid', () => {
  it('composes 5 Reel children', () => {
    const grid = new SlotGrid({
      cellSize: 100,
      textures: makeTextureMap([1, 2, 3, 4, 5, 7, 8, 9, 11, 12]),
    });
    expect(grid.children).toHaveLength(5);
    expect(grid.reelCount).toBe(5);
    for (const child of grid.children) {
      expect(child).toBeInstanceOf(Reel);
    }
  });

  it('renderMatrix produces 15 sprite children with the expected textures', () => {
    const ids = [1, 2, 3, 4, 5, 7, 8, 9, 11, 12];
    const textures = makeTextureMap(ids);
    const grid = new SlotGrid({ cellSize: 100, textures });

    grid.renderMatrix(FIXTURE_MATRIX);

    const sprites = grid.children.flatMap((reel) => reel.children) as Sprite[];
    expect(sprites).toHaveLength(15);

    // Spot-check column 0 (sprites 0-2) and column 4 (sprites 12-14).
    expect(sprites[0]!.texture).toBe(textures.get(2));
    expect(sprites[1]!.texture).toBe(textures.get(3));
    expect(sprites[2]!.texture).toBe(textures.get(7));

    expect(sprites[12]!.texture).toBe(textures.get(9));
    expect(sprites[13]!.texture).toBe(textures.get(4));
    expect(sprites[14]!.texture).toBe(textures.get(11));
  });

  it('renderMatrix throws on the wrong row count', () => {
    const grid = new SlotGrid({ cellSize: 100, textures: makeTextureMap([1]) });
    expect(() => grid.renderMatrix([[1, 2, 3, 4, 5]])).toThrow(/expected 3 rows/);
  });

  it('renderMatrix throws on missing cells', () => {
    const grid = new SlotGrid({ cellSize: 100, textures: makeTextureMap([1]) });
    expect(() =>
      grid.renderMatrix([
        [1, 2, 3, 4, 5],
        [1, 2, 3, 4],
        [1, 2, 3, 4, 5],
      ]),
    ).toThrow(/missing cell/);
  });

  it('reports its pixel dimensions accounting for reel gap', () => {
    const grid = new SlotGrid({ cellSize: 100, reelGap: 10, textures: new Map() });
    expect(grid.pixelWidth).toBe(5 * 100 + 4 * 10);
    expect(grid.pixelHeight).toBe(3 * 100);
  });
});
