import { useMutation, useQuery } from '@tanstack/react-query';
import { useState } from 'react';

import { getRound, type RoundInspection } from '@/api/admin/getRound';
import { replay, type RoundReplayResult } from '@/api/admin/replay';
import { pushToast } from '@/ui/toast/toastStore';

import styles from './InspectorTab.module.css';
import { JsonView } from './JsonView';
import { MatrixView } from './MatrixView';
import replayStyles from './ReplayTab.module.css';

function extractMatrix(value: unknown): unknown {
  if (value && typeof value === 'object' && 'matrix' in value) {
    return (value as { matrix: unknown }).matrix;
  }
  return null;
}

function matricesEqual(a: unknown, b: unknown): boolean {
  if (!Array.isArray(a) || !Array.isArray(b)) return false;
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) {
    const ra = a[i];
    const rb = b[i];
    if (!Array.isArray(ra) || !Array.isArray(rb)) return false;
    if (ra.length !== rb.length) return false;
    for (let j = 0; j < ra.length; j++) {
      if (ra[j] !== rb[j]) return false;
    }
  }
  return true;
}

export function ReplayTab(): JSX.Element {
  const [roundId, setRoundId] = useState('r-3001');
  const [submittedId, setSubmittedId] = useState<string | null>(null);

  const roundQuery = useQuery<RoundInspection, Error>({
    queryKey: ['admin', 'round', submittedId],
    queryFn: () => getRound(submittedId as string),
    enabled: submittedId !== null,
    retry: 0,
  });

  const replayMutation = useMutation<RoundReplayResult, Error, string>({
    mutationFn: (id) => replay(id),
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
    setSubmittedId(trimmed);
    replayMutation.mutate(trimmed);
  };

  const storedMatrix = roundQuery.data?.matrix ?? null;
  const replayedMatrix = extractMatrix(replayMutation.data);
  const ready =
    roundQuery.isSuccess && replayMutation.isSuccess && replayedMatrix !== null;
  const matches = ready && matricesEqual(storedMatrix, replayedMatrix);

  return (
    <section className={styles.tab} aria-label="Round replay">
      <h2 className={styles.heading}>Round replay</h2>
      <p className={styles.helper}>
        Reconstructs a round deterministically via <code>POST /api/v1/admin/replay/{'{roundId}'}</code>{' '}
        and compares to the stored matrix.
      </p>

      <form className={styles.form} onSubmit={handleSubmit}>
        <label className={styles.field} htmlFor="replay-id">
          Round id
        </label>
        <input
          id="replay-id"
          className={styles.input}
          value={roundId}
          onChange={(e) => setRoundId(e.target.value)}
          aria-invalid={roundId.trim().length === 0}
        />
        <button
          type="submit"
          className={styles.submit}
          disabled={replayMutation.isPending || roundQuery.isFetching}
          aria-busy={replayMutation.isPending || roundQuery.isFetching}
        >
          {replayMutation.isPending || roundQuery.isFetching ? 'Replaying…' : 'Replay'}
        </button>
      </form>

      {replayMutation.isError && (
        <p className={styles.error} role="alert">
          {replayMutation.error.message}
        </p>
      )}
      {roundQuery.isError && (
        <p className={styles.error} role="alert">
          {roundQuery.error.message}
        </p>
      )}

      {ready && (
        <div
          className={matches ? replayStyles.badgeMatch : replayStyles.badgeMismatch}
          role="status"
          aria-live="polite"
        >
          {matches ? 'MATCH — replay output equals stored round' : 'MISMATCH — outputs differ'}
        </div>
      )}

      {(roundQuery.data || replayMutation.data) && (
        <div className={replayStyles.sideBySide}>
          <div className={replayStyles.column}>
            <h3>Stored matrix</h3>
            <MatrixView matrix={storedMatrix} />
          </div>
          <div className={replayStyles.column}>
            <h3>Replayed matrix</h3>
            <MatrixView matrix={replayedMatrix} />
          </div>
        </div>
      )}

      {replayMutation.data && (
        <>
          <h3>Raw replay result</h3>
          <JsonView data={replayMutation.data} initiallyExpanded={false} label="Replay JSON" />
        </>
      )}
    </section>
  );
}
