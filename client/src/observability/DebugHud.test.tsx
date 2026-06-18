import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { useAuthStore } from '@/auth/authStore';
import { useSessionStore } from '@/session/sessionStore';
import { useWalletStore } from '@/wallet/walletStore';

vi.mock('@/env', () => ({
  env: { VITE_ENABLE_DEBUG_HUD: true },
}));

import { DebugHud } from './DebugHud';
import { _resetRecentTraces, recordTrace } from './recentTraces';

afterEach(() => {
  useAuthStore.getState().clear();
  useSessionStore.getState().reset();
  useWalletStore.getState().reset();
  _resetRecentTraces();
});

describe('DebugHud', () => {
  beforeEach(() => {
    useAuthStore.setState({
      token: 'tok',
      playerId: 'p-1001',
      sessionId: 's-2001',
      currency: 'EUR',
      roles: ['PLAYER'],
      expiresAt: new Date(Date.now() + 60_000),
    });
    useSessionStore.setState({
      sessionId: 's-2001',
      gameId: 'aztec-fire',
      sessionVersion: 7,
      currentState: 'BASE_GAME',
    });
  });

  it('renders a collapsed toggle that expands on click', async () => {
    const user = userEvent.setup();
    render(<DebugHud />);
    const toggle = screen.getByRole('button', { name: /debug/i });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await user.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByText('p-1001')).toBeInTheDocument();
    expect(screen.getByText('s-2001')).toBeInTheDocument();
    expect(screen.getByText('aztec-fire')).toBeInTheDocument();
    expect(screen.getByText('7')).toBeInTheDocument();
    expect(screen.getByText('BASE_GAME')).toBeInTheDocument();
  });

  it('lists recent traces emitted via recordTrace', async () => {
    const user = userEvent.setup();
    render(<DebugHud />);
    await user.click(screen.getByRole('button', { name: /debug/i }));
    expect(screen.getByText(/no traces recorded/i)).toBeInTheDocument();
    recordTrace('toast:warn', 'trace-aaa');
    recordTrace('toast:error', 'trace-bbb');
    expect(await screen.findByText('trace-aaa')).toBeInTheDocument();
    expect(screen.getByText('trace-bbb')).toBeInTheDocument();
  });
});

describe('DebugHud (disabled)', () => {
  it('returns null when VITE_ENABLE_DEBUG_HUD is false', async () => {
    vi.resetModules();
    vi.doMock('@/env', () => ({ env: { VITE_ENABLE_DEBUG_HUD: false } }));
    const { DebugHud: Disabled } = await import('./DebugHud');
    const { container } = render(<Disabled />);
    expect(container.firstChild).toBeNull();
    vi.doUnmock('@/env');
  });
});
