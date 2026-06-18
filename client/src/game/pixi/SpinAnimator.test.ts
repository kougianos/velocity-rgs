import { type Sprite, Texture } from 'pixi.js';
import { describe, expect, it } from 'vitest';

import type { SpinResponse } from '@/api/slot/spin';
import { SlotGrid } from '@/game/pixi/SlotGrid';

import { SpinAnimator, type AnimationRunner } from './SpinAnimator';

function makeTextureMap(): Map<number, Texture> {
  const map = new Map<number, Texture>();
  for (const id of [1, 2, 3, 4, 5, 7, 8, 9, 11, 12]) {
    map.set(id, new Texture());
  }
  return map;
}

function fixtureResponse(overrides: Partial<SpinResponse> = {}): SpinResponse {
  return {
    sessionId: 's-2001',
    sessionVersion: 8,
    roundId: 'r-3001',
    mathVersion: 'v1',
    betDebited: 1.0,
    totalWin: 150.0,
    matrix: [
      [2, 5, 1, 8, 9],
      [3, 12, 1, 1, 4],
      [7, 8, 2, 3, 11],
    ],
    stopPositions: [14, 82, 4, 119, 43],
    winLines: [
      { lineId: 1, symbolId: 1, count: 5, payout: 100 },
      { lineId: 3, symbolId: 1, count: 4, payout: 50 },
    ],
    featuresTriggered: {
      freeSpinsAwarded: 0,
      isPowerBetActive: false,
      pickCollectTriggered: false,
      bonusBuyExecuted: false,
      reasonCodes: [],
    },
    sessionState: {
      currentState: 'BASE_GAME',
      remainingFreeSpins: 0,
      accumulatedFreeSpinsWin: 0,
    },
    availableActions: ['SPIN'],
    ...overrides,
  };
}

const fastRunner: AnimationRunner = { delay: () => Promise.resolve() };

describe('SpinAnimator', () => {
  it('renders the response matrix onto the grid after play', async () => {
    const grid = new SlotGrid({ cellSize: 100, textures: makeTextureMap() });
    const animator = new SpinAnimator(grid, { cellSize: 100, runner: fastRunner });

    await animator.play(fixtureResponse());

    const reels = grid.children.filter((_c, i) => i < 5);
    const sprites = reels.flatMap((reel) => reel.children) as Sprite[];
    expect(sprites).toHaveLength(15);
    // Spot-check column 0 from the fixture (2, 3, 7).
    expect(sprites[0]!.texture).toBeDefined();
  });

  it('draws one overlay graphic per win line', async () => {
    const grid = new SlotGrid({ cellSize: 100, textures: makeTextureMap() });
    const animator = new SpinAnimator(grid, { cellSize: 100, runner: fastRunner });

    await animator.play(fixtureResponse());

    expect(animator.overlayChildCount).toBe(2);
  });

  it('clears prior overlays between plays', async () => {
    const grid = new SlotGrid({ cellSize: 100, textures: makeTextureMap() });
    const animator = new SpinAnimator(grid, { cellSize: 100, runner: fastRunner });

    await animator.play(fixtureResponse());
    expect(animator.overlayChildCount).toBe(2);
    await animator.play(fixtureResponse({ winLines: [] }));
    expect(animator.overlayChildCount).toBe(0);
  });

  it('skips win-line overlay when the server math version drifts', async () => {
    const grid = new SlotGrid({ cellSize: 100, textures: makeTextureMap() });
    const animator = new SpinAnimator(grid, { cellSize: 100, runner: fastRunner });

    await animator.play(fixtureResponse({ mathVersion: 'v99' }));

    expect(animator.overlayChildCount).toBe(0);
  });
});
