import { beforeEach, describe, expect, it } from 'vitest';

import type { WalletBalanceResponse } from '@/api/wallet/balance';
import type { WalletDebitResponse } from '@/api/wallet/debit';

import { useWalletStore } from './walletStore';

const canonicalBalance: WalletBalanceResponse = {
  playerId: 'p-1001',
  balance: 98.5,
  currency: 'EUR',
};

const canonicalDebit: WalletDebitResponse = {
  transactionId: 't-4001',
  status: 'SUCCESS',
  balanceBefore: 100.0,
  balanceAfter: 98.5,
  currency: 'EUR',
  processedAt: '2026-06-17T10:15:30Z',
  idempotentReplay: false,
};

describe('walletStore', () => {
  beforeEach(() => {
    useWalletStore.getState().reset();
  });

  it('applyBalance mirrors the canonical balance response', () => {
    useWalletStore.getState().applyBalance(canonicalBalance);
    const s = useWalletStore.getState();
    expect(s.currency).toBe('EUR');
    expect(s.balance?.toPlain()).toBe(98.5);
    expect(s.lastUpdatedAt).toBeInstanceOf(Date);
  });

  it('applyTransactionEffect sets balance to balanceAfter (Appendix A.6)', () => {
    useWalletStore.getState().applyTransactionEffect(canonicalDebit);
    const s = useWalletStore.getState();
    expect(s.balance?.toPlain()).toBe(98.5);
    expect(s.currency).toBe('EUR');
  });

  it('reset() returns the store to its initial empty state', () => {
    useWalletStore.getState().applyBalance(canonicalBalance);
    useWalletStore.getState().reset();
    const s = useWalletStore.getState();
    expect(s.balance).toBeNull();
    expect(s.currency).toBeNull();
    expect(s.lastUpdatedAt).toBeNull();
  });

  it('rejects unsupported currencies', () => {
    expect(() =>
      useWalletStore
        .getState()
        .applyBalance({ playerId: 'p-1001', balance: 1, currency: 'GBP' }),
    ).toThrow(/Unsupported currency/);
  });
});
