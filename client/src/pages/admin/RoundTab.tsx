import { useMutation } from '@tanstack/react-query';
import { useMemo, useState } from 'react';

import { getRound, type RoundInspection } from '@/api/admin/getRound';
import { pushToast } from '@/ui/toast/toastStore';

import styles from './InspectorTab.module.css';
import { JsonView } from './JsonView';
import { MatrixView } from './MatrixView';

interface WinLine {
  readonly lineId: number;
  readonly symbolId: number;
  readonly count: number;
  readonly payout: number;
}

function isWinLineArray(value: unknown): value is WinLine[] {
  return (
    Array.isArray(value) &&
    value.every(
      (entry) =>
        typeof entry === 'object' &&
        entry !== null &&
        typeof (entry as { lineId?: unknown }).lineId === 'number' &&
        typeof (entry as { symbolId?: unknown }).symbolId === 'number' &&
        typeof (entry as { count?: unknown }).count === 'number' &&
        typeof (entry as { payout?: unknown }).payout === 'number',
    )
  );
}

export function RoundTab(): JSX.Element {
  const [roundId, setRoundId] = useState('r-3001');

  const mutation = useMutation<RoundInspection, Error, string>({
    mutationFn: (id) => getRound(id),
    retry: 0,
    onError: (err) => pushToast('error', err.message),
  });

  const handleSubmit = (e: React.FormEvent): void => {
    e.preventDefault();
    const trimmed = roundId.trim();
    if (trimmed.length === 0) {
      pushToast('warn', 'Round id is required');
      return;
    }
    mutation.mutate(trimmed);
  };

  const winLines = useMemo<WinLine[]>(() => {
    const raw = mutation.data?.winLines;
    return isWinLineArray(raw) ? raw : [];
  }, [mutation.data]);

  return (
    <section className={styles.tab} aria-label="Round inspector">
      <h2 className={styles.heading}>Round inspector</h2>
      <p className={styles.helper}>
        Loads stored round details via <code>GET /api/v1/admin/round/{'{roundId}'}</code> and
        renders matrix, win lines and RNG draws.
      </p>

      <form className={styles.form} onSubmit={handleSubmit}>
        <label className={styles.field} htmlFor="round-id">
          Round id
        </label>
        <input
          id="round-id"
          className={styles.input}
          value={roundId}
          onChange={(e) => setRoundId(e.target.value)}
          aria-invalid={roundId.trim().length === 0}
        />
        <button
          type="submit"
          className={styles.submit}
          disabled={mutation.isPending}
          aria-busy={mutation.isPending}
        >
          {mutation.isPending ? 'Loading…' : 'Load round'}
        </button>
      </form>

      {mutation.isError && (
        <p className={styles.error} role="alert">
          {mutation.error.message}
        </p>
      )}

      {mutation.data && (
        <div className={styles.result}>
          <header className={styles.resultHeader}>
            <span>
              Round <strong>{mutation.data.roundId}</strong> — bet {mutation.data.betAmount}{' '}
              {mutation.data.currency} → win {mutation.data.totalWin} {mutation.data.currency}
            </span>
          </header>

          <h3>Matrix</h3>
          <MatrixView matrix={mutation.data.matrix} />

          {winLines.length > 0 && (
            <>
              <h3>Win lines</h3>
              <table className={styles.table}>
                <thead>
                  <tr>
                    <th>Line</th>
                    <th>Symbol</th>
                    <th>Count</th>
                    <th>Payout</th>
                  </tr>
                </thead>
                <tbody>
                  {winLines.map((line) => (
                    <tr key={line.lineId}>
                      <td>{line.lineId}</td>
                      <td>{line.symbolId}</td>
                      <td>{line.count}</td>
                      <td>{line.payout}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </>
          )}

          <h3>RNG draws</h3>
          <JsonView data={mutation.data.rngDraws ?? null} label="RNG draws" />

          <h3>Raw round</h3>
          <JsonView data={mutation.data} initiallyExpanded={false} label="Round JSON" />
        </div>
      )}
    </section>
  );
}
