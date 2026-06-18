import { useMemo, useState } from 'react';

import styles from './JsonView.module.css';

export interface JsonViewProps {
  data: unknown;
  initiallyExpanded?: boolean;
  label?: string;
}

export function JsonView({ data, initiallyExpanded = true, label }: JsonViewProps): JSX.Element {
  return (
    <div className={styles.root} aria-label={label ?? 'JSON tree'}>
      <Node value={data} keyName="root" depth={0} initiallyExpanded={initiallyExpanded} />
    </div>
  );
}

interface NodeProps {
  value: unknown;
  keyName: string;
  depth: number;
  initiallyExpanded: boolean;
}

function Node({ value, keyName, depth, initiallyExpanded }: NodeProps): JSX.Element {
  const [expanded, setExpanded] = useState(initiallyExpanded || depth < 1);

  if (value === null) return <Leaf keyName={keyName} text="null" className={styles.null ?? ''} />;
  if (typeof value === 'string') return <Leaf keyName={keyName} text={`"${value}"`} className={styles.string ?? ''} />;
  if (typeof value === 'number') return <Leaf keyName={keyName} text={String(value)} className={styles.number ?? ''} />;
  if (typeof value === 'boolean') return <Leaf keyName={keyName} text={String(value)} className={styles.boolean ?? ''} />;
  if (typeof value === 'undefined') return <Leaf keyName={keyName} text="undefined" className={styles.null ?? ''} />;

  const isArray = Array.isArray(value);
  const entries = isArray
    ? (value as unknown[]).map((v, i) => [String(i), v] as const)
    : Object.entries(value as Record<string, unknown>);
  const open = isArray ? '[' : '{';
  const close = isArray ? ']' : '}';

  if (entries.length === 0) {
    return <Leaf keyName={keyName} text={`${open}${close}`} className={styles.empty ?? ''} />;
  }

  return (
    <div className={styles.branch}>
      <button
        type="button"
        className={styles.toggle}
        onClick={() => setExpanded((e) => !e)}
        aria-expanded={expanded}
        aria-label={`${expanded ? 'Collapse' : 'Expand'} ${keyName}`}
      >
        <span className={styles.caret} aria-hidden="true">
          {expanded ? '▾' : '▸'}
        </span>
        <span className={styles.key}>{keyName}</span>
        <span className={styles.bracket}>
          {open}
          {!expanded && <span className={styles.summary}>{entries.length} items</span>}
          {!expanded && close}
        </span>
      </button>
      {expanded && (
        <div className={styles.children}>
          {entries.map(([k, v]) => (
            <Node key={k} keyName={k} value={v} depth={depth + 1} initiallyExpanded={false} />
          ))}
          <span className={styles.bracket}>{close}</span>
        </div>
      )}
    </div>
  );
}

interface LeafProps {
  keyName: string;
  text: string;
  className: string;
}

function Leaf({ keyName, text, className }: LeafProps): JSX.Element {
  const display = useMemo(() => text, [text]);
  return (
    <div className={styles.leaf}>
      <span className={styles.key}>{keyName}</span>
      <span className={styles.colon}>:</span>
      <span className={className}>{display}</span>
    </div>
  );
}
