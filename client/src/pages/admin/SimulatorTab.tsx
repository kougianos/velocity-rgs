import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation } from '@tanstack/react-query';
import { useMemo } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';

import {
  simulatorRun,
  type RtpReport,
  type RtpSimulationRequest,
} from '@/api/admin/simulatorRun';
import { pushToast } from '@/ui/toast/toastStore';

import styles from './InspectorTab.module.css';
import simStyles from './SimulatorTab.module.css';

const PICK_STRATEGIES = ['SEQUENTIAL', 'RANDOM_UNOPENED', 'COLLECT_FIRST'] as const;

const schema = z.object({
  gameId: z.string().min(1, 'Game id is required'),
  mathVersion: z.string().min(1, 'Math version is required'),
  bet: z
    .union([z.string(), z.number()])
    .transform((v) => (typeof v === 'string' ? Number(v) : v))
    .pipe(z.number().positive('Bet must be > 0')),
  spinsBaseGame: z
    .union([z.string(), z.number()])
    .transform((v) => (typeof v === 'string' ? Number(v) : v))
    .pipe(z.number().int().nonnegative('Spins must be ≥ 0')),
  spinsBonusBuyFreeSpins: z
    .union([z.string(), z.number()])
    .transform((v) => (typeof v === 'string' ? Number(v) : v))
    .pipe(z.number().int().nonnegative('Spins must be ≥ 0')),
  spinsBonusBuyPickCollect: z
    .union([z.string(), z.number()])
    .transform((v) => (typeof v === 'string' ? Number(v) : v))
    .pipe(z.number().int().nonnegative('Spins must be ≥ 0')),
  pickStrategy: z.enum(PICK_STRATEGIES),
});

type FormValues = z.input<typeof schema>;
type ParsedFormValues = z.output<typeof schema>;

const DEFAULTS: FormValues = {
  gameId: 'aztec-fire',
  mathVersion: 'v1',
  bet: 1,
  spinsBaseGame: 1000,
  spinsBonusBuyFreeSpins: 0,
  spinsBonusBuyPickCollect: 0,
  pickStrategy: 'SEQUENTIAL',
};

export function SimulatorTab(): JSX.Element {
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: DEFAULTS,
  });

  const mutation = useMutation<RtpReport, Error, RtpSimulationRequest>({
    mutationFn: (req) => simulatorRun(req),
    retry: 0,
    onError: (err) => pushToast('error', err.message),
  });

  const onSubmit = (raw: FormValues): void => {
    const parsed = schema.parse(raw) as ParsedFormValues;
    mutation.mutate(parsed);
  };

  const channels = useMemo(
    () => (mutation.data ? Object.entries(mutation.data.channels) : []),
    [mutation.data],
  );

  const maxRtp = useMemo(() => {
    if (channels.length === 0) return 0;
    return channels.reduce((max, [, ch]) => Math.max(max, Math.abs(ch.rtpPercent)), 0);
  }, [channels]);

  return (
    <section className={styles.tab} aria-label="Simulator">
      <h2 className={styles.heading}>RTP simulator</h2>
      <p className={styles.helper}>
        Runs Monte-Carlo spins via <code>POST /api/v1/admin/simulator/run</code>.
      </p>

      <form className={simStyles.formGrid} onSubmit={handleSubmit(onSubmit)} noValidate>
        <Field
          id="sim-gameId"
          label="Game id"
          register={register('gameId')}
          {...(errors.gameId?.message ? { error: errors.gameId.message } : {})}
        />
        <Field
          id="sim-mathVersion"
          label="Math version"
          register={register('mathVersion')}
          {...(errors.mathVersion?.message ? { error: errors.mathVersion.message } : {})}
        />
        <Field
          id="sim-bet"
          label="Bet"
          register={register('bet')}
          type="number"
          step="0.01"
          min={0}
          {...(errors.bet?.message ? { error: errors.bet.message } : {})}
        />
        <Field
          id="sim-baseSpins"
          label="Base spins"
          register={register('spinsBaseGame')}
          type="number"
          min={0}
          {...(errors.spinsBaseGame?.message ? { error: errors.spinsBaseGame.message } : {})}
        />
        <Field
          id="sim-fsBuySpins"
          label="Free-spins buy spins"
          register={register('spinsBonusBuyFreeSpins')}
          type="number"
          min={0}
          {...(errors.spinsBonusBuyFreeSpins?.message
            ? { error: errors.spinsBonusBuyFreeSpins.message }
            : {})}
        />
        <Field
          id="sim-pcBuySpins"
          label="Pick-collect buy spins"
          register={register('spinsBonusBuyPickCollect')}
          type="number"
          min={0}
          {...(errors.spinsBonusBuyPickCollect?.message
            ? { error: errors.spinsBonusBuyPickCollect.message }
            : {})}
        />

        <div className={simStyles.fullRow}>
          <label className={styles.field} htmlFor="sim-pickStrategy">
            Pick strategy
          </label>
          <select
            id="sim-pickStrategy"
            className={styles.input}
            {...register('pickStrategy')}
          >
            {PICK_STRATEGIES.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </div>

        <button
          type="submit"
          className={simStyles.submit}
          disabled={mutation.isPending}
          aria-busy={mutation.isPending}
        >
          {mutation.isPending ? 'Running…' : 'Run simulation'}
        </button>
      </form>

      {mutation.isError && (
        <p className={styles.error} role="alert">
          {mutation.error.message}
        </p>
      )}

      {mutation.data && (
        <div className={simStyles.report} aria-label="Simulation report">
          <header className={simStyles.reportHeader}>
            <span>
              Run <strong>{mutation.data.runId}</strong> — {mutation.data.gameId} @{' '}
              {mutation.data.mathVersion}
            </span>
            <span>elapsed {mutation.data.elapsedMillis} ms</span>
          </header>

          <dl className={simStyles.metaGrid}>
            <dt>Bet</dt>
            <dd>{mutation.data.bet}</dd>
            <dt>Overall spins</dt>
            <dd>{mutation.data.overall.spins}</dd>
            <dt>Overall RTP</dt>
            <dd>{mutation.data.overall.rtpPercent}%</dd>
            <dt>Free-spin triggers</dt>
            <dd>{mutation.data.freeSpinTriggers}</dd>
            <dt>Pick entries</dt>
            <dd>{mutation.data.pickEntries}</dd>
          </dl>

          <h3>RTP by channel</h3>
          <table className={styles.table}>
            <thead>
              <tr>
                <th>Channel</th>
                <th>Spins</th>
                <th>Total bet</th>
                <th>Total win</th>
                <th>RTP %</th>
              </tr>
            </thead>
            <tbody>
              {channels.map(([name, ch]) => (
                <tr key={name}>
                  <td>{name}</td>
                  <td>{ch.spins}</td>
                  <td>{ch.totalBet}</td>
                  <td>{ch.totalWin}</td>
                  <td>{ch.rtpPercent}</td>
                </tr>
              ))}
              <tr className={simStyles.totalRow}>
                <td>OVERALL</td>
                <td>{mutation.data.overall.spins}</td>
                <td>{mutation.data.overall.totalBet}</td>
                <td>{mutation.data.overall.totalWin}</td>
                <td>{mutation.data.overall.rtpPercent}</td>
              </tr>
            </tbody>
          </table>

          <h3>RTP distribution</h3>
          <div className={simStyles.chart} role="img" aria-label="RTP percentage by channel">
            {channels.map(([name, ch]) => {
              const widthPct = maxRtp === 0 ? 0 : (Math.abs(ch.rtpPercent) / maxRtp) * 100;
              return (
                <div key={name} className={simStyles.bar}>
                  <span className={simStyles.barLabel}>{name}</span>
                  <span
                    className={simStyles.barFill}
                    style={{ width: `${widthPct}%` }}
                  />
                  <span className={simStyles.barValue}>{ch.rtpPercent}%</span>
                </div>
              );
            })}
          </div>
        </div>
      )}
    </section>
  );
}

interface FieldProps {
  id: string;
  label: string;
  register: ReturnType<ReturnType<typeof useForm<FormValues>>['register']>;
  type?: string;
  step?: string;
  min?: number;
  error?: string;
}

function Field({ id, label, register, type = 'text', step, min, error }: FieldProps): JSX.Element {
  return (
    <div className={simStyles.field}>
      <label className={styles.field} htmlFor={id}>
        {label}
      </label>
      <input
        id={id}
        className={styles.input}
        type={type}
        {...(step !== undefined ? { step } : {})}
        {...(min !== undefined ? { min } : {})}
        aria-invalid={error !== undefined}
        {...register}
      />
      {error && <p className={styles.error}>{error}</p>}
    </div>
  );
}
