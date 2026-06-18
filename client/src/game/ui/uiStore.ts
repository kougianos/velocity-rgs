import { create } from 'zustand';

/**
 * Lightweight HUD-only UI state. The PowerBet toggle (Task 6.6) and any
 * other purely client-visual flags live here so server-state stores stay
 * untouched. Nothing in this store influences game outcomes.
 */
export interface UiStore {
  powerBetActive: boolean;
  setPowerBetActive: (value: boolean) => void;
  togglePowerBet: () => void;
  reset: () => void;
}

export const useUiStore = create<UiStore>((set) => ({
  powerBetActive: false,
  setPowerBetActive: (value) => set({ powerBetActive: value }),
  togglePowerBet: () => set((s) => ({ powerBetActive: !s.powerBetActive })),
  reset: () => set({ powerBetActive: false }),
}));
