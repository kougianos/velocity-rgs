import { create } from 'zustand';

import type { GameCommand, GameState } from '@/api/enums';
import type { components } from '@/api/generated/openapi';
import type { FeatureBuyResponse } from '@/api/slot/featureBuy';
import type { FeaturePickResponse } from '@/api/slot/featurePick';
import type { FeatureStartResponse } from '@/api/slot/featureStart';
import type { SlotInitResponse } from '@/api/slot/init';
import type { SpinResponse } from '@/api/slot/spin';
import { Money, type Currency } from '@/common/money/Money';

export type ActiveFeatureView = components['schemas']['PickCollectFeatureView'];

interface FeatureFlags {
  bonusBuyEnabled: boolean;
  powerBetEnabled: boolean;
}

export interface SessionState {
  sessionId: string | null;
  sessionVersion: number | null;
  gameId: string | null;
  mathVersion: string | null;
  currency: Currency | null;
  currentState: GameState | null;
  remainingFreeSpins: number;
  accumulatedFreeSpinsWin: Money | null;
  currentBet: Money | null;
  availableActions: GameCommand[];
  featureFlags: FeatureFlags;
  activeFeatureView: ActiveFeatureView | null;
  lastSpin: SpinResponse | null;
  lastPick: FeaturePickResponse | null;
}

export interface SessionStore extends SessionState {
  applyInitResponse: (r: SlotInitResponse) => void;
  applySpinResponse: (r: SpinResponse) => void;
  applyFeatureStartResponse: (r: FeatureStartResponse) => void;
  applyFeatureBuyResponse: (r: FeatureBuyResponse) => void;
  applyPickResponse: (r: FeaturePickResponse) => void;
  setCurrentBet: (bet: Money) => void;
  reset: () => void;
}

const initial: SessionState = {
  sessionId: null,
  sessionVersion: null,
  gameId: null,
  mathVersion: null,
  currency: null,
  currentState: null,
  remainingFreeSpins: 0,
  accumulatedFreeSpinsWin: null,
  currentBet: null,
  availableActions: [],
  featureFlags: { bonusBuyEnabled: false, powerBetEnabled: false },
  activeFeatureView: null,
  lastSpin: null,
  lastPick: null,
};

function assertCurrency(value: string): Currency {
  if (value === 'EUR' || value === 'USD') return value;
  throw new Error(`Unsupported currency from server: ${value}`);
}

function readFlag(flags: Record<string, boolean> | undefined, key: string): boolean {
  return flags?.[key] === true;
}

function isStale(currentVersion: number | null, incomingVersion: number): boolean {
  return currentVersion !== null && incomingVersion <= currentVersion;
}

function warnStale(label: string, currentVersion: number | null, incomingVersion: number): void {
  // eslint-disable-next-line no-console
  console.warn(
    `[sessionStore] dropped stale ${label} response (current=${String(currentVersion)}, incoming=${incomingVersion})`,
  );
}

export const useSessionStore = create<SessionStore>((set, get) => ({
  ...initial,

  applyInitResponse: (r) => {
    const currency = assertCurrency(r.currency);
    set({
      sessionId: r.sessionId,
      sessionVersion: r.sessionVersion,
      gameId: r.gameId,
      mathVersion: r.mathVersion,
      currency,
      currentState: r.currentState,
      remainingFreeSpins: r.remainingFreeSpins,
      accumulatedFreeSpinsWin: Money.fromNumber(r.accumulatedFreeSpinsWin, currency),
      currentBet:
        r.currentBet === undefined || r.currentBet === null
          ? null
          : Money.fromNumber(r.currentBet, currency),
      availableActions: [...r.availableActions],
      featureFlags: {
        bonusBuyEnabled: readFlag(r.featureFlags, 'bonusBuyEnabled'),
        powerBetEnabled: readFlag(r.featureFlags, 'powerBetEnabled'),
      },
      activeFeatureView: r.activeFeatureView ?? null,
      lastSpin: null,
      lastPick: null,
    });
  },

  applySpinResponse: (r) => {
    const current = get();
    if (isStale(current.sessionVersion, r.sessionVersion)) {
      warnStale('spin', current.sessionVersion, r.sessionVersion);
      return;
    }
    const currency = current.currency ?? 'EUR';
    set({
      sessionVersion: r.sessionVersion,
      mathVersion: r.mathVersion,
      currentState: r.sessionState.currentState,
      remainingFreeSpins: r.sessionState.remainingFreeSpins,
      accumulatedFreeSpinsWin: Money.fromNumber(r.sessionState.accumulatedFreeSpinsWin, currency),
      availableActions: [...r.availableActions],
      lastSpin: r,
    });
  },

  applyFeatureStartResponse: (r) => {
    const current = get();
    if (isStale(current.sessionVersion, r.sessionVersion)) {
      warnStale('feature/start', current.sessionVersion, r.sessionVersion);
      return;
    }
    const currency = current.currency ?? 'EUR';
    set({
      sessionVersion: r.sessionVersion,
      currentState: r.currentState,
      remainingFreeSpins: r.remainingFreeSpins,
      accumulatedFreeSpinsWin:
        r.accumulatedFreeSpinsWin === undefined || r.accumulatedFreeSpinsWin === null
          ? current.accumulatedFreeSpinsWin
          : Money.fromNumber(r.accumulatedFreeSpinsWin, currency),
      activeFeatureView: r.activeFeatureView ?? null,
      availableActions: [...r.availableActions],
    });
  },

  applyFeatureBuyResponse: (r) => {
    const current = get();
    if (isStale(current.sessionVersion, r.sessionVersion)) {
      warnStale('feature/buy', current.sessionVersion, r.sessionVersion);
      return;
    }
    set({
      sessionVersion: r.sessionVersion,
      currentState: r.enteredState,
      activeFeatureView: r.activeFeatureView ?? null,
      availableActions: [...r.availableActions],
    });
  },

  applyPickResponse: (r) => {
    const current = get();
    if (isStale(current.sessionVersion, r.sessionVersion)) {
      warnStale('feature/pick', current.sessionVersion, r.sessionVersion);
      return;
    }
    set({
      sessionVersion: r.sessionVersion,
      currentState: r.currentState ?? current.currentState,
      activeFeatureView: r.activeFeatureView ?? current.activeFeatureView,
      availableActions: [...r.availableActions],
      lastPick: r,
    });
  },

  setCurrentBet: (bet) => {
    set({ currentBet: bet });
  },

  reset: () => set({ ...initial }),
}));
