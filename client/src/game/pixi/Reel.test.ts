import { Sprite, Texture } from 'pixi.js';
import { describe, expect, it } from 'vitest';

import { Reel } from './Reel';

function makeTextureMap(ids: number[]): Map<number, Texture> {
  const map = new Map<number, Texture>();
  for (const id of ids) {
    map.set(id, new Texture());
  }
  return map;
}

describe('Reel', () => {
  it('creates exactly GRID.rows (3) sprite children', () => {
    const reel = new Reel({ cellSize: 100, textures: makeTextureMap([1, 2, 3]) });
    expect(reel.children).toHaveLength(3);
    expect(reel.cellCount).toBe(3);
    for (const child of reel.children) {
      expect(child).toBeInstanceOf(Sprite);
    }
  });

  it('setSymbols assigns the matching texture per row', () => {
    const textures = makeTextureMap([1, 2, 3]);
    const reel = new Reel({ cellSize: 100, textures });
    reel.setSymbols([1, 2, 3]);
    expect((reel.children[0] as Sprite).texture).toBe(textures.get(1));
    expect((reel.children[1] as Sprite).texture).toBe(textures.get(2));
    expect((reel.children[2] as Sprite).texture).toBe(textures.get(3));
  });

  it('setSymbols falls back to Texture.EMPTY for unknown symbol ids', () => {
    const textures = makeTextureMap([1]);
    const reel = new Reel({ cellSize: 100, textures });
    reel.setSymbols([1, 999, 1]);
    expect((reel.children[1] as Sprite).texture).toBe(Texture.EMPTY);
  });

  it('setSymbols throws when the column length does not match GRID.rows', () => {
    const reel = new Reel({ cellSize: 100, textures: makeTextureMap([1]) });
    expect(() => reel.setSymbols([1, 2])).toThrow(/expected 3 symbols/);
  });
});
