import { getSymbolName, GRID } from '@/game/math/aztec-fire';

import styles from './MatrixView.module.css';

export interface MatrixViewProps {
  matrix: unknown;
  highlightCoords?: ReadonlyArray<readonly [number, number]>;
}

function isMatrix(value: unknown): value is number[][] {
  if (!Array.isArray(value)) return false;
  if (value.length !== GRID.rows) return false;
  return value.every(
    (row) =>
      Array.isArray(row) &&
      row.length === GRID.cols &&
      row.every((cell) => typeof cell === 'number'),
  );
}

export function MatrixView({ matrix, highlightCoords }: MatrixViewProps): JSX.Element {
  if (!isMatrix(matrix)) {
    return (
      <div className={styles.empty} role="status">
        No matrix data
      </div>
    );
  }

  const highlight = new Set<string>(
    (highlightCoords ?? []).map(([r, c]) => `${r}:${c}`),
  );

  return (
    <div
      className={styles.grid}
      role="grid"
      aria-rowcount={GRID.rows}
      aria-colcount={GRID.cols}
      style={{ '--cols': GRID.cols } as React.CSSProperties}
    >
      {matrix.map((row, r) =>
        row.map((symbolId, c) => {
          const key = `${r}:${c}`;
          const className = highlight.has(key)
            ? `${styles.cell} ${styles.highlight}`
            : styles.cell;
          return (
            <div
              key={key}
              className={className}
              role="gridcell"
              aria-label={`Row ${r + 1} column ${c + 1}: ${getSymbolName(symbolId)}`}
            >
              <span className={styles.id}>{symbolId}</span>
              <span className={styles.name}>{getSymbolName(symbolId)}</span>
            </div>
          );
        }),
      )}
    </div>
  );
}
