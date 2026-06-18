import { useState } from 'react';
import { Link } from 'react-router-dom';

import { ReplayTab } from './admin/ReplayTab';
import { RoundTab } from './admin/RoundTab';
import { SessionTab } from './admin/SessionTab';
import { SimulatorTab } from './admin/SimulatorTab';
import { WalletTab } from './admin/WalletTab';
import styles from './AdminPage.module.css';

type TabId = 'wallet' | 'session' | 'round' | 'replay' | 'simulator';

interface TabDef {
  readonly id: TabId;
  readonly label: string;
  readonly render: () => JSX.Element;
}

const TABS: readonly TabDef[] = [
  { id: 'wallet', label: 'Wallet', render: () => <WalletTab /> },
  { id: 'session', label: 'Session', render: () => <SessionTab /> },
  { id: 'round', label: 'Round', render: () => <RoundTab /> },
  { id: 'replay', label: 'Replay', render: () => <ReplayTab /> },
  { id: 'simulator', label: 'Simulator', render: () => <SimulatorTab /> },
];

export function AdminPage(): JSX.Element {
  const [activeId, setActiveId] = useState<TabId>('wallet');
  const active = TABS.find((t) => t.id === activeId) ?? TABS[0]!;

  return (
    <main className={styles.admin}>
      <header className={styles.header}>
        <div>
          <h1 className={styles.title}>Admin tools</h1>
          <p className={styles.subtitle}>
            Set balance, inspect sessions/rounds, replay outcomes, run RTP simulations.
          </p>
        </div>
        <Link to="/play" className={styles.backLink}>
          ← Back to game
        </Link>
      </header>

      <div className={styles.tabs} role="tablist" aria-label="Admin sections">
        {TABS.map((tab) => {
          const isActive = tab.id === activeId;
          return (
            <button
              key={tab.id}
              type="button"
              role="tab"
              id={`tab-${tab.id}`}
              aria-selected={isActive}
              aria-controls={`panel-${tab.id}`}
              className={isActive ? `${styles.tab} ${styles.tabActive}` : styles.tab}
              onClick={() => setActiveId(tab.id)}
            >
              {tab.label}
            </button>
          );
        })}
      </div>

      <section
        className={styles.panel}
        role="tabpanel"
        id={`panel-${active.id}`}
        aria-labelledby={`tab-${active.id}`}
      >
        {active.render()}
      </section>
    </main>
  );
}
