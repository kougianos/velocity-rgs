import { describe, expect, it } from 'vitest';

import {
  BONUS_BUY_OPTIONS,
  GAME_ID,
  GRID,
  getPayline,
  getSymbol,
  getSymbolName,
  MATH_VERSION,
  PAYLINES,
  POWER_BET,
  SYMBOLS,
} from './aztec-fire';

describe('aztec-fire math mirror', () => {
  it('declares the correct game id and version', () => {
    expect(GAME_ID).toBe('aztec-fire');
    expect(MATH_VERSION).toBe('v1');
    expect(GRID).toEqual({ rows: 3, cols: 5 });
  });

  it('exposes the 10 reel symbols from the math config', () => {
    expect(SYMBOLS).toHaveLength(10);
    expect(getSymbol(9)?.kind).toBe('WILD');
    expect(getSymbol(12)?.kind).toBe('SCATTER');
    expect(getSymbol(1)?.name).toBe('ACE');
    expect(getSymbolName(999)).toBe('?999');
  });

  it('exposes 20 paylines with 5 coords each', () => {
    expect(PAYLINES).toHaveLength(20);
    for (const line of PAYLINES) {
      expect(line.coords).toHaveLength(5);
      for (const [row, col] of line.coords) {
        expect(row).toBeGreaterThanOrEqual(0);
        expect(row).toBeLessThan(GRID.rows);
        expect(col).toBeGreaterThanOrEqual(0);
        expect(col).toBeLessThan(GRID.cols);
      }
    }
    expect(getPayline(1)?.coords[0]).toEqual([0, 0]);
  });

  it('exposes the bonus buy options matching the server math', () => {
    expect(BONUS_BUY_OPTIONS).toHaveLength(2);
    const fs = BONUS_BUY_OPTIONS.find((o) => o.buyType === 'FREE_SPINS_BUY');
    expect(fs?.costMultiplier).toBe(80);
    expect(fs?.targetState).toBe('FREE_SPINS_AWAITING');
    const pc = BONUS_BUY_OPTIONS.find((o) => o.buyType === 'PICK_COLLECT_BUY');
    expect(pc?.costMultiplier).toBe(120);
  });

  it('exposes the power-bet multiplier', () => {
    expect(POWER_BET.betMultiplier).toBe(1.5);
  });
});
