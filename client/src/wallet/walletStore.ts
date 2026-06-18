import { create } from 'zustand';

import type { WalletBalanceResponse } from '@/api/wallet/balance';
import type { WalletCreditResponse } from '@/api/wallet/credit';
import type { WalletDebitResponse } from '@/api/wallet/debit';
import { Money, type Currency } from '@/common/money/Money';

export type WalletTransactionResponse = WalletDebitResponse | WalletCreditResponse;

export interface WalletState {
  balance: Money | null;
  currency: Currency | null;
  lastUpdatedAt: Date | null;
}

export interface WalletStore extends WalletState {
  applyBalance: (r: WalletBalanceResponse) => void;
  applyTransactionEffect: (r: WalletTransactionResponse) => void;
  reset: () => void;
}

const initial: WalletState = {
  balance: null,
  currency: null,
  lastUpdatedAt: null,
};

function assertCurrency(value: string): Currency {
  if (value === 'EUR' || value === 'USD') return value;
  throw new Error(`Unsupported currency from server: ${value}`);
}

export const useWalletStore = create<WalletStore>((set) => ({
  ...initial,

  applyBalance: (r) => {
    const currency = assertCurrency(r.currency);
    set({
      balance: Money.fromNumber(r.balance, currency),
      currency,
      lastUpdatedAt: new Date(),
    });
  },

  applyTransactionEffect: (r) => {
    const currency = assertCurrency(r.currency);
    set({
      balance: Money.fromNumber(r.balanceAfter, currency),
      currency,
      lastUpdatedAt: new Date(),
    });
  },

  reset: () => set({ ...initial }),
}));
