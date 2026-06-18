import { Route, Routes } from 'react-router-dom';

import { DebugPage } from '@/pages/DebugPage';
import { LobbyPage } from '@/pages/LobbyPage';

export function App(): JSX.Element {
  return (
    <Routes>
      <Route path="/" element={<LobbyPage />} />
      <Route path="/debug" element={<DebugPage />} />
      <Route path="*" element={<LobbyPage />} />
    </Routes>
  );
}
