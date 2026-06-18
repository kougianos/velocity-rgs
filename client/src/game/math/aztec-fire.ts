/**
 * Static client-side mirror of `server/src/main/resources/math/aztec-fire/v1.json`.
 *
 * Per §7.1 (gap-report): no `/game/math-summary` endpoint exists, so this
 * mirror exists purely to drive presentation concerns (symbol names, payline
 * coordinates for win-line overlays, bonus-buy panel display, power-bet
 * caption). Every monetary value rendered on screen must still come from a
 * server response — the mirror NEVER computes outcomes.
 *
 * The server stamps `mathVersion` on every response. If the active server
 * version differs from `MATH_VERSION` below, callers must downgrade their
 * presentation (e.g. skip win-line overlays) rather than show stale art.
 */

import type { BonusBuyType, GameState } from '@/api/enums';

export const GAME_ID = 'aztec-fire' as const;
export const MATH_VERSION = 'v1' as const;

export const GRID = { rows: 3, cols: 5 } as const;

export type SymbolKind = 'STANDARD' | 'WILD' | 'SCATTER';

export interface SymbolMeta {
  readonly id: number;
  readonly name: string;
  readonly kind: SymbolKind;
}

export const SYMBOLS: readonly SymbolMeta[] = [
  { id: 1, name: 'ACE', kind: 'STANDARD' },
  { id: 2, name: 'KING', kind: 'STANDARD' },
  { id: 3, name: 'QUEEN', kind: 'STANDARD' },
  { id: 4, name: 'JACK', kind: 'STANDARD' },
  { id: 5, name: 'TEN', kind: 'STANDARD' },
  { id: 6, name: 'NINE', kind: 'STANDARD' },
  { id: 7, name: 'STATUE', kind: 'STANDARD' },
  { id: 8, name: 'MASK', kind: 'STANDARD' },
  { id: 9, name: 'WILD', kind: 'WILD' },
  { id: 12, name: 'SCATTER', kind: 'SCATTER' },
];

const SYMBOLS_BY_ID = new Map<number, SymbolMeta>(SYMBOLS.map((s) => [s.id, s]));

export function getSymbol(id: number): SymbolMeta | undefined {
  return SYMBOLS_BY_ID.get(id);
}

export function getSymbolName(id: number): string {
  return SYMBOLS_BY_ID.get(id)?.name ?? `?${id}`;
}

/** Payline coordinates: each `[row, col]` pair (row 0..2, col 0..4). */
export type PaylineCoord = readonly [number, number];

export interface Payline {
  readonly id: number;
  readonly coords: readonly PaylineCoord[];
}

export const PAYLINES: readonly Payline[] = [
  { id: 1, coords: [[0, 0], [0, 1], [0, 2], [0, 3], [0, 4]] },
  { id: 2, coords: [[1, 0], [1, 1], [1, 2], [1, 3], [1, 4]] },
  { id: 3, coords: [[2, 0], [2, 1], [2, 2], [2, 3], [2, 4]] },
  { id: 4, coords: [[0, 0], [1, 1], [2, 2], [1, 3], [0, 4]] },
  { id: 5, coords: [[2, 0], [1, 1], [0, 2], [1, 3], [2, 4]] },
  { id: 6, coords: [[0, 0], [0, 1], [1, 2], [2, 3], [2, 4]] },
  { id: 7, coords: [[2, 0], [2, 1], [1, 2], [0, 3], [0, 4]] },
  { id: 8, coords: [[1, 0], [0, 1], [1, 2], [2, 3], [1, 4]] },
  { id: 9, coords: [[1, 0], [2, 1], [1, 2], [0, 3], [1, 4]] },
  { id: 10, coords: [[0, 0], [1, 1], [1, 2], [1, 3], [0, 4]] },
  { id: 11, coords: [[2, 0], [1, 1], [1, 2], [1, 3], [2, 4]] },
  { id: 12, coords: [[1, 0], [0, 1], [0, 2], [0, 3], [1, 4]] },
  { id: 13, coords: [[1, 0], [2, 1], [2, 2], [2, 3], [1, 4]] },
  { id: 14, coords: [[0, 0], [1, 1], [0, 2], [1, 3], [0, 4]] },
  { id: 15, coords: [[2, 0], [1, 1], [2, 2], [1, 3], [2, 4]] },
  { id: 16, coords: [[0, 0], [2, 1], [0, 2], [2, 3], [0, 4]] },
  { id: 17, coords: [[2, 0], [0, 1], [2, 2], [0, 3], [2, 4]] },
  { id: 18, coords: [[1, 0], [1, 1], [0, 2], [1, 3], [1, 4]] },
  { id: 19, coords: [[1, 0], [1, 1], [2, 2], [1, 3], [1, 4]] },
  { id: 20, coords: [[0, 0], [2, 1], [2, 2], [2, 3], [0, 4]] },
];

const PAYLINES_BY_ID = new Map<number, Payline>(PAYLINES.map((p) => [p.id, p]));

export function getPayline(id: number): Payline | undefined {
  return PAYLINES_BY_ID.get(id);
}

export const POWER_BET = { betMultiplier: 1.5 } as const;

export interface BonusBuyOption {
  readonly buyType: BonusBuyType;
  readonly costMultiplier: number;
  readonly targetState: GameState;
}

export const BONUS_BUY_OPTIONS: readonly BonusBuyOption[] = [
  { buyType: 'FREE_SPINS_BUY', costMultiplier: 80, targetState: 'FREE_SPINS_AWAITING' },
  { buyType: 'PICK_COLLECT_BUY', costMultiplier: 120, targetState: 'PICK_COLLECT_AWAITING' },
];
