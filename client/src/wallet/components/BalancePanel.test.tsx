import { render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import { Money } from '@/common/money/Money';

import { useWalletStore } from '../walletStore';

import { BalancePanel } from './BalancePanel';

describe('BalancePanel', () => {
  afterEach(() => {
    useWalletStore.getState().reset();
  });

  it('shows the skeleton when balance is null', () => {
    render(<BalancePanel />);
    expect(screen.getByRole('status')).toHaveAttribute('aria-label', 'Balance loading');
    expect(screen.getByText('…')).toBeInTheDocument();
  });

  it('renders the formatted balance with currency once loaded', () => {
    useWalletStore.setState({
      balance: Money.fromNumber(98.5, 'EUR'),
      currency: 'EUR',
      lastUpdatedAt: new Date(),
    });
    render(<BalancePanel />);
    expect(screen.getByText(/€98\.50/)).toBeInTheDocument();
  });
});
