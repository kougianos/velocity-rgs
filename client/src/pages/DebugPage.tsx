import { useState } from 'react';

import { setAuthToken } from '@/api/http/authToken';
import { RgsHttpError, RgsNetworkError } from '@/api/http/errors';
import { init, type SlotInitResponse } from '@/api/slot/init';
import { env } from '@/env';

import styles from './DebugPage.module.css';

interface DebugError {
  kind: 'http' | 'network' | 'unknown';
  message: string;
  code?: string;
  traceId?: string;
}

/**
 * M1 smoke surface (Task 1.11). Fires `/api/v1/slot/init` against the
 * configured backend and pretty-prints the response. To be removed in M9.
 */
export function DebugPage(): JSX.Element {
  const [token, setToken] = useState('');
  const [response, setResponse] = useState<SlotInitResponse | null>(null);
  const [error, setError] = useState<DebugError | null>(null);
  const [loading, setLoading] = useState(false);

  async function fire(): Promise<void> {
    setLoading(true);
    setResponse(null);
    setError(null);
    setAuthToken(token.trim() === '' ? null : token.trim());
    try {
      const data = await init({
        gameId: env.VITE_DEFAULT_GAME_ID,
        currency: env.VITE_DEFAULT_CURRENCY,
      });
      setResponse(data);
    } catch (err) {
      if (err instanceof RgsHttpError) {
        setError({
          kind: 'http',
          message: err.message,
          code: err.code,
          traceId: err.traceId,
        });
      } else if (err instanceof RgsNetworkError) {
        setError({ kind: 'network', message: err.message });
      } else {
        setError({ kind: 'unknown', message: String(err) });
      }
    } finally {
      setLoading(false);
    }
  }

  return (
    <main className={styles.debug}>
      <h1 className={styles.title}>Debug — /api/v1/slot/init</h1>
      <p className={styles.subtitle}>
        Smoke surface introduced in M1 — removed in M9. Target: {env.VITE_API_BASE_URL}
      </p>

      <label className={styles.field} htmlFor="debug-token">
        Bearer token (optional)
      </label>
      <textarea
        id="debug-token"
        className={styles.tokenInput}
        rows={3}
        value={token}
        onChange={(e) => setToken(e.target.value)}
        placeholder="Paste a JWT to attach as Authorization: Bearer …"
      />

      <button
        type="button"
        className={styles.fireButton}
        onClick={() => {
          void fire();
        }}
        disabled={loading}
      >
        {loading ? 'Firing…' : `Fire init (${env.VITE_DEFAULT_GAME_ID}, ${env.VITE_DEFAULT_CURRENCY})`}
      </button>

      {error && (
        <section className={styles.errorBlock} aria-live="polite">
          <h2>Error · {error.kind}</h2>
          <dl>
            {error.code && (
              <>
                <dt>code</dt>
                <dd>{error.code}</dd>
              </>
            )}
            {error.traceId && (
              <>
                <dt>traceId</dt>
                <dd>{error.traceId}</dd>
              </>
            )}
            <dt>message</dt>
            <dd>{error.message}</dd>
          </dl>
        </section>
      )}

      {response && (
        <section className={styles.responseBlock} aria-live="polite">
          <h2>Response</h2>
          <pre className={styles.json}>{JSON.stringify(response, null, 2)}</pre>
        </section>
      )}
    </main>
  );
}
