import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { HttpResponse, http as mswHttp } from 'msw';
import { setupServer } from 'msw/node';
import type { ReactNode } from 'react';
import { MemoryRouter } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';

import { useAuthStore } from '@/auth/authStore';
import { handlers } from '@/mocks/handlers';
import { useSessionStore } from '@/session/sessionStore';
import { useToastStore } from '@/ui/toast/toastStore';
import { useWalletStore } from '@/wallet/walletStore';

import { AdminPage } from './AdminPage';

function Wrap({ children }: { children: ReactNode }): JSX.Element {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: 0 }, mutations: { retry: 0 } },
  });
  return (
    <QueryClientProvider client={qc}>
      <MemoryRouter>{children}</MemoryRouter>
    </QueryClientProvider>
  );
}

const server = setupServer(...handlers);

describe('AdminPage', () => {
  beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
  afterEach(() => {
    server.resetHandlers();
    useAuthStore.getState().clear();
    useSessionStore.getState().reset();
    useWalletStore.getState().reset();
    useToastStore.getState().clear();
  });
  afterAll(() => server.close());

  beforeEach(() => {
    useAuthStore.setState({
      token: 'tok',
      playerId: 'p-1001',
      sessionId: 's-2001',
      currency: 'EUR',
      roles: ['ADMIN'],
      expiresAt: new Date(Date.now() + 3600_000),
    });
  });

  it('renders the wallet tab by default with a populated player id field', async () => {
    render(
      <Wrap>
        <AdminPage />
      </Wrap>,
    );
    const tab = screen.getByRole('tab', { name: /wallet/i });
    expect(tab).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByLabelText(/player id/i)).toHaveValue('p-1001');
  });

  it('switches between tabs', async () => {
    render(
      <Wrap>
        <AdminPage />
      </Wrap>,
    );
    await userEvent.click(screen.getByRole('tab', { name: /session/i }));
    expect(screen.getByRole('heading', { name: /session inspector/i })).toBeInTheDocument();

    await userEvent.click(screen.getByRole('tab', { name: /round/i }));
    expect(screen.getByRole('heading', { name: /round inspector/i })).toBeInTheDocument();

    await userEvent.click(screen.getByRole('tab', { name: /replay/i }));
    expect(screen.getByRole('heading', { name: /round replay/i })).toBeInTheDocument();

    await userEvent.click(screen.getByRole('tab', { name: /simulator/i }));
    expect(screen.getByRole('heading', { name: /rtp simulator/i })).toBeInTheDocument();
  });

  describe('Wallet tab', () => {
    it('rejects a negative balance', async () => {
      render(
        <Wrap>
          <AdminPage />
        </Wrap>,
      );
      const balanceInput = screen.getByLabelText(/balance/i);
      await userEvent.clear(balanceInput);
      await userEvent.type(balanceInput, '-10');
      await userEvent.click(screen.getByRole('button', { name: /set balance/i }));
      expect(await screen.findByText(/must be ≥ 0/i)).toBeInTheDocument();
    });

    it('updates the wallet store when the edited player matches the session player', async () => {
      let body: unknown = null;
      server.use(
        mswHttp.post('*/api/v1/admin/wallet/balance', async ({ request }) => {
          body = await request.json();
          return HttpResponse.json({
            playerId: 'p-1001',
            currency: 'EUR',
            balance: 777,
            version: 5,
            updatedAt: '2026-06-17T10:15:30Z',
          });
        }),
      );
      render(
        <Wrap>
          <AdminPage />
        </Wrap>,
      );
      const balanceInput = screen.getByLabelText(/balance/i);
      await userEvent.clear(balanceInput);
      await userEvent.type(balanceInput, '777');
      await userEvent.click(screen.getByRole('button', { name: /set balance/i }));

      await waitFor(() => {
        expect(useWalletStore.getState().balance?.toPlain()).toBe(777);
      });
      expect(body).toEqual({ playerId: 'p-1001', currency: 'EUR', balance: 777 });
    });
  });

  describe('Session tab', () => {
    it('shows the redis cache badge on success', async () => {
      render(
        <Wrap>
          <AdminPage />
        </Wrap>,
      );
      await userEvent.click(screen.getByRole('tab', { name: /session/i }));
      await userEvent.click(screen.getByRole('button', { name: /inspect/i }));
      expect(await screen.findByText(/REDIS/)).toBeInTheDocument();
    });
  });

  describe('Round tab', () => {
    it('renders the matrix and win lines from the round inspection', async () => {
      render(
        <Wrap>
          <AdminPage />
        </Wrap>,
      );
      await userEvent.click(screen.getByRole('tab', { name: /round/i }));
      await userEvent.click(screen.getByRole('button', { name: /load round/i }));
      await waitFor(() => {
        expect(screen.getByRole('grid')).toBeInTheDocument();
      });
      // 15 cells = 3 rows × 5 cols
      expect(screen.getAllByRole('gridcell')).toHaveLength(15);
      // win lines table: payout 150 appears in both the table row and the JSON view
      expect(screen.getAllByText('150').length).toBeGreaterThanOrEqual(1);
    });
  });

  describe('Replay tab', () => {
    it('shows the MATCH badge when reconstructed matrix equals stored', async () => {
      render(
        <Wrap>
          <AdminPage />
        </Wrap>,
      );
      await userEvent.click(screen.getByRole('tab', { name: /replay/i }));
      await userEvent.click(screen.getByRole('button', { name: /replay/i }));
      const status = await screen.findByText(/match — replay output equals stored round/i);
      expect(status).toBeInTheDocument();
    });

    it('shows MISMATCH when matrices differ', async () => {
      server.use(
        mswHttp.post('*/api/v1/admin/replay/:roundId', () =>
          HttpResponse.json({
            roundId: 'r-3001',
            matches: false,
            matrix: [
              [1, 1, 1, 1, 1],
              [1, 1, 1, 1, 1],
              [1, 1, 1, 1, 1],
            ],
          }),
        ),
      );
      render(
        <Wrap>
          <AdminPage />
        </Wrap>,
      );
      await userEvent.click(screen.getByRole('tab', { name: /replay/i }));
      await userEvent.click(screen.getByRole('button', { name: /replay/i }));
      expect(await screen.findByText(/mismatch/i)).toBeInTheDocument();
    });
  });

  describe('Simulator tab', () => {
    it('rejects a bet ≤ 0', async () => {
      render(
        <Wrap>
          <AdminPage />
        </Wrap>,
      );
      await userEvent.click(screen.getByRole('tab', { name: /simulator/i }));
      const betInput = screen.getByLabelText(/^bet$/i);
      await userEvent.clear(betInput);
      await userEvent.type(betInput, '0');
      await userEvent.click(screen.getByRole('button', { name: /run simulation/i }));
      expect(await screen.findByText(/must be > 0/i)).toBeInTheDocument();
    });

    it('renders the RTP report with channels and the overall row', async () => {
      render(
        <Wrap>
          <AdminPage />
        </Wrap>,
      );
      await userEvent.click(screen.getByRole('tab', { name: /simulator/i }));
      await userEvent.click(screen.getByRole('button', { name: /run simulation/i }));
      const report = await screen.findByLabelText(/simulation report/i);
      expect(report).toBeInTheDocument();
      expect(screen.getAllByText('BASE').length).toBeGreaterThanOrEqual(1);
      expect(screen.getAllByText('FREE_SPINS_BUY').length).toBeGreaterThanOrEqual(1);
      expect(screen.getAllByText('PICK_COLLECT_BUY').length).toBeGreaterThanOrEqual(1);
      expect(screen.getByText('OVERALL')).toBeInTheDocument();
    });
  });
});
