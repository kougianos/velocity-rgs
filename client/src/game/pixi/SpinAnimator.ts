import { Container, Graphics } from 'pixi.js';

import type { SpinResponse } from '@/api/slot/spin';
import { GRID, getPayline, MATH_VERSION } from '@/game/math/aztec-fire';
import type { Reel } from '@/game/pixi/Reel';
import type { SlotGrid } from '@/game/pixi/SlotGrid';

/**
 * Deterministic Pixi animator for a single {@link SpinResponse}.
 *
 * The matrix, stop positions, and win lines all come from the server (Q1/Q7).
 * Nothing in this class makes a game decision; it just paints the reels and
 * traces the canonical paylines from `src/game/math/aztec-fire.ts`.
 *
 * Timing budgets follow Appendix E:
 *   - reel decel:        ≤ 1200 ms (80 ms stagger × 5 reels + ~600 ms tail)
 *   - win-line highlight: ≤ 800 ms total
 *
 * Determinism: same `SpinResponse` ⇒ identical visuals. No `Math.random()`.
 */
export interface SpinAnimatorOptions {
  /** Side length of one cell, in px. Should match the grid's `cellSize`. */
  cellSize: number;
  /** Gap between reels, in px. Should match the grid's `reelGap`. */
  reelGap?: number;
  /** Optional override for the reel spin window total duration (ms). */
  spinDurationMs?: number;
  /** Optional override for the win-line highlight pass total duration (ms). */
  winLineDurationMs?: number;
  /** Test seam: animation runner. Defaults to `requestAnimationFrame`-based runner. */
  runner?: AnimationRunner;
}

export interface AnimationRunner {
  delay: (ms: number) => Promise<void>;
}

const defaultRunner: AnimationRunner = {
  delay: (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
};

const REEL_STAGGER_MS = 80;
const REEL_TAIL_MS = 600;
const WIN_LINE_HOLD_MS = 200;

export class SpinAnimator {
  private readonly grid: SlotGrid;
  private readonly cellSize: number;
  private readonly reelGap: number;
  private readonly spinDurationMs: number;
  private readonly winLineDurationMs: number;
  private readonly runner: AnimationRunner;
  private readonly overlay: Container;

  constructor(grid: SlotGrid, options: SpinAnimatorOptions) {
    this.grid = grid;
    this.cellSize = options.cellSize;
    this.reelGap = options.reelGap ?? 8;
    this.spinDurationMs = options.spinDurationMs ?? REEL_STAGGER_MS * GRID.cols + REEL_TAIL_MS;
    this.winLineDurationMs = options.winLineDurationMs ?? 800;
    this.runner = options.runner ?? defaultRunner;

    this.overlay = new Container();
    this.overlay.label = 'win-lines';
    this.grid.addChild(this.overlay);
  }

  async play(response: SpinResponse): Promise<void> {
    this.clearOverlay();

    await this.spinReels();
    this.grid.renderMatrix(response.matrix);

    if (response.mathVersion !== MATH_VERSION) {
      // eslint-disable-next-line no-console
      console.warn(
        `[SpinAnimator] math version drift: client=${MATH_VERSION} server=${response.mathVersion}; skipping win-line overlay`,
      );
      return;
    }

    if (response.winLines.length > 0) {
      await this.highlightWinLines(response.winLines);
    }
  }

  /** Test-only: number of overlay graphics currently drawn. */
  get overlayChildCount(): number {
    return this.overlay.children.length;
  }

  /** Frees Pixi resources. Call when unmounting. */
  destroy(): void {
    this.overlay.destroy({ children: true });
  }

  // --- internals ---

  private async spinReels(): Promise<void> {
    const reels = this.gridReels();
    const offset = this.cellSize * 0.6;
    const originalYs = reels.map((r) => r.y);

    // Shift each reel down to suggest motion; stagger the lift back per col.
    reels.forEach((reel, idx) => {
      reel.y = originalYs[idx]! + offset;
      reel.alpha = 0.85;
    });

    const totalMs = this.spinDurationMs;
    await this.runner.delay(totalMs);

    reels.forEach((reel, idx) => {
      reel.y = originalYs[idx]!;
      reel.alpha = 1;
    });
  }

  private async highlightWinLines(
    winLines: SpinResponse['winLines'],
  ): Promise<void> {
    const perLineMs = Math.max(
      120,
      Math.floor((this.winLineDurationMs - WIN_LINE_HOLD_MS) / Math.max(winLines.length, 1)),
    );
    for (const line of winLines) {
      this.drawPayline(line.lineId);
      await this.runner.delay(perLineMs);
    }
    await this.runner.delay(WIN_LINE_HOLD_MS);
  }

  private drawPayline(lineId: number): void {
    const payline = getPayline(lineId);
    if (!payline) return;
    const g = new Graphics();
    const stride = this.cellSize + this.reelGap;
    const points = payline.coords.map(([row, col]) => ({
      x: col * stride + this.cellSize / 2,
      y: row * this.cellSize + this.cellSize / 2,
    }));
    const first = points[0];
    if (!first) return;
    g.moveTo(first.x, first.y);
    for (let i = 1; i < points.length; i++) {
      const p = points[i]!;
      g.lineTo(p.x, p.y);
    }
    g.stroke({ color: 0xf1c40f, width: 4, alpha: 0.9, alignment: 0.5 });
    this.overlay.addChild(g);
  }

  private clearOverlay(): void {
    this.overlay.removeChildren().forEach((c) => c.destroy());
  }

  private gridReels(): Reel[] {
    // SlotGrid composes its reels as the first `GRID.cols` children; the
    // overlay container is added afterwards (see constructor).
    return this.grid.children.slice(0, GRID.cols) as Reel[];
  }
}
