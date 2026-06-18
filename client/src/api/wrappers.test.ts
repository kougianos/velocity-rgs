import { HttpResponse, http } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';

import { getRound } from '@/api/admin/getRound';
import { getSession } from '@/api/admin/getSession';
import { replay } from '@/api/admin/replay';
import { setBalance } from '@/api/admin/setBalance';
import { simulatorRun } from '@/api/admin/simulatorRun';
import { devToken } from '@/api/dev/token';
import { setAuthToken } from '@/api/http/authToken';
import { RgsHttpError } from '@/api/http/errors';
import { featureBuy } from '@/api/slot/featureBuy';
import { featurePick } from '@/api/slot/featurePick';
import { featureStart } from '@/api/slot/featureStart';
import { init } from '@/api/slot/init';
import { spin } from '@/api/slot/spin';
import { authenticate } from '@/api/wallet/authenticate';
import { balance } from '@/api/wallet/balance';
import { credit } from '@/api/wallet/credit';
import { debit } from '@/api/wallet/debit';
import { rollback } from '@/api/wallet/rollback';
import { handlers } from '@/mocks/handlers';

const server = setupServer(...handlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe('typed API wrappers', () => {
  it('init returns the typed canonical fixture', async () => {
    const resp = await init({ gameId: 'aztec-fire', currency: 'EUR' });
    expect(resp.sessionId).toBe('s-2001');
    expect(resp.sessionVersion).toBe(7);
    expect(resp.currentState).toBe('FREE_SPINS_AWAITING');
    expect(resp.availableActions).toEqual(['START_FREE_SPINS']);
  });

  it('spin attaches Idempotency-Key and X-Trace-Id headers', async () => {
    const seen: Record<string, string | null> = {};
    server.use(
      http.post('*/api/v1/slot/spin', ({ request }) => {
        seen['idempotency-key'] = request.headers.get('Idempotency-Key');
        seen['x-trace-id'] = request.headers.get('X-Trace-Id');
        seen['authorization'] = request.headers.get('Authorization');
        return HttpResponse.json({
          sessionId: 's-2001',
          sessionVersion: 8,
          roundId: 'r-3001',
          mathVersion: 'v1',
          betDebited: 1,
          totalWin: 0,
          matrix: [
            [1, 2, 3, 4, 5],
            [1, 2, 3, 4, 5],
            [1, 2, 3, 4, 5],
          ],
          stopPositions: [0, 0, 0, 0, 0],
          winLines: [],
          featuresTriggered: {
            freeSpinsAwarded: 0,
            isPowerBetActive: false,
            pickCollectTriggered: false,
            bonusBuyExecuted: false,
            reasonCodes: [],
          },
          sessionState: {
            currentState: 'BASE_GAME',
            remainingFreeSpins: 0,
            accumulatedFreeSpinsWin: 0,
          },
          availableActions: ['SPIN'],
        });
      }),
    );
    setAuthToken('jwt-stub');
    const key = '11111111-2222-4333-8444-555555555555';
    await spin(key, {
      gameId: 'aztec-fire',
      sessionId: 's-2001',
      sessionVersion: 7,
      betSize: 1,
      powerBetActive: false,
    });
    expect(seen['idempotency-key']).toBe(key);
    expect(seen['authorization']).toBe('Bearer jwt-stub');
    expect(seen['x-trace-id']).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i,
    );
    setAuthToken(null);
  });

  it('does not attach Authorization when no token is set', async () => {
    setAuthToken(null);
    let header: string | null = null;
    server.use(
      http.get('*/api/v1/wallet/balance', ({ request }) => {
        header = request.headers.get('Authorization');
        return HttpResponse.json({ playerId: 'p-1001', balance: 1, currency: 'EUR' });
      }),
    );
    await balance();
    expect(header).toBeNull();
  });

  it('feature/start, feature/buy, feature/pick parse their fixtures', async () => {
    const startResp = await featureStart('k1', {
      gameId: 'aztec-fire',
      sessionId: 's-2001',
      sessionVersion: 8,
      featureType: 'FREE_SPINS',
    });
    expect(startResp.currentState).toBe('FREE_SPINS_LOOP');

    const buyResp = await featureBuy('k2', {
      gameId: 'aztec-fire',
      sessionId: 's-2001',
      sessionVersion: 8,
      buyType: 'FREE_SPINS_BUY',
      betSize: 1,
    });
    expect(buyResp.cost).toBe(80);
    expect(buyResp.enteredState).toBe('FREE_SPINS_AWAITING');

    const pickResp = await featurePick('k3', {
      gameId: 'aztec-fire',
      sessionId: 's-2001',
      sessionVersion: 12,
      position: 4,
    });
    expect(pickResp.resolvedTileType).toBe('MULTIPLIER');
    expect(pickResp.featureCompleted).toBe(false);
  });

  it('wallet debit / credit / rollback / authenticate', async () => {
    const debResp = await debit('kd', {
      playerId: 'p-1001',
      sessionId: 's-2001',
      roundId: 'r-3001',
      transactionId: 't-4001',
      amount: 1.5,
      currency: 'EUR',
      transactionType: 'BET',
    });
    expect(debResp.balanceAfter).toBe(98.5);

    const credResp = await credit('kc', {
      playerId: 'p-1001',
      sessionId: 's-2001',
      roundId: 'r-3001',
      transactionId: 't-4002',
      amount: 150,
      currency: 'EUR',
      transactionType: 'WIN',
    });
    expect(credResp.balanceAfter).toBe(248.5);

    const rbResp = await rollback('kr', {
      playerId: 'p-1001',
      originalTransactionId: 't-4001',
      transactionId: 't-5001',
      rollbackReason: 'DOWNSTREAM_FAILURE',
    });
    expect(rbResp.originalTransactionId).toBe('t-4001');

    const authResp = await authenticate({ playerId: 'p-1001' });
    expect(authResp.eligible).toBe(true);
  });

  it('dev token, admin setBalance, getSession, getRound, replay, simulatorRun', async () => {
    server.use(
      http.post('*/api/v1/admin/wallet/balance', () =>
        HttpResponse.json({
          playerId: 'p-1001',
          currency: 'EUR',
          balance: 500,
          version: 3,
          updatedAt: '2026-06-17T10:15:30Z',
        }),
      ),
      http.get('*/api/v1/admin/session/p-1001', () =>
        HttpResponse.json({
          sessionId: 's-2001',
          playerId: 'p-1001',
          gameId: 'aztec-fire',
          mathVersion: 'v1',
          currency: 'EUR',
          currentBet: 1,
          remainingFreeSpins: 0,
          accumulatedFreeSpinsWin: 0,
          sessionVersion: 7,
          createdAt: '2026-06-17T10:00:00Z',
          updatedAt: '2026-06-17T10:15:30Z',
          cachedInRedis: true,
          activeFeaturePayload: null,
        }),
      ),
      http.get('*/api/v1/admin/round/r-3001', () =>
        HttpResponse.json({
          roundId: 'r-3001',
          sessionId: 's-2001',
          playerId: 'p-1001',
          gameId: 'aztec-fire',
          mathVersion: 'v1',
          betAmount: 1,
          totalWin: 150,
          currency: 'EUR',
          powerBetActive: false,
          createdAt: '2026-06-17T10:15:30Z',
        }),
      ),
      http.post('*/api/v1/admin/replay/r-3001', () =>
        HttpResponse.json({ roundId: 'r-3001', matches: true }),
      ),
      http.post('*/api/v1/admin/simulator/run', () =>
        HttpResponse.json({
          runId: 'sim-1',
          gameId: 'aztec-fire',
          mathVersion: 'v1',
          bet: 1,
          channels: {
            BASE: { spins: 100, totalBet: 100, totalWin: 95, rtpPercent: 95 },
          },
          overall: { spins: 100, totalBet: 100, totalWin: 95, rtpPercent: 95 },
          elapsedMillis: 42,
          generatedAt: '2026-06-17T10:15:30Z',
          freeSpinTriggers: 0,
          pickEntries: 0,
        }),
      ),
    );

    expect((await devToken({ playerId: 'p', sessionId: 's', currency: 'EUR' })).token).toContain(
      'eyJ',
    );
    expect(
      (
        await setBalance({ playerId: 'p-1001', currency: 'EUR', balance: 500 })
      ).version,
    ).toBe(3);
    expect((await getSession('p-1001')).sessionId).toBe('s-2001');
    expect((await getRound('r-3001')).roundId).toBe('r-3001');
    expect((await replay('r-3001')) as { matches: boolean }).toEqual({
      roundId: 'r-3001',
      matches: true,
    });
    expect(
      (
        await simulatorRun({
          gameId: 'aztec-fire',
          mathVersion: 'v1',
          bet: 1,
          spinsBaseGame: 100,
          spinsBonusBuyFreeSpins: 0,
          spinsBonusBuyPickCollect: 0,
        })
      ).overall.rtpPercent,
    ).toBe(95);
  });

  it('propagates RgsHttpError on a 4xx response', async () => {
    server.use(
      http.post('*/api/v1/slot/spin', () =>
        HttpResponse.json(
          {
            code: 'INSUFFICIENT_FUNDS',
            message: 'Wallet debit failed',
            httpStatus: 409,
            traceId: 'c8c90d1f-24df-4cd3-95e2-33d3015d5d31',
            timestamp: '2026-06-17T10:15:30Z',
          },
          { status: 409 },
        ),
      ),
    );
    await expect(
      spin('k-x', {
        gameId: 'aztec-fire',
        sessionId: 's-2001',
        sessionVersion: 7,
        betSize: 1,
        powerBetActive: false,
      }),
    ).rejects.toBeInstanceOf(RgsHttpError);
  });
});
