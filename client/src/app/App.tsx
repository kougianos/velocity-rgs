import { Route, Routes } from 'react-router-dom';

import { LobbyPage } from '@/pages/LobbyPage';

export function App(): JSX.Element {
  return (
    <Routes>
      <Route path="/" element={<LobbyPage />} />
      <Route path="*" element={<LobbyPage />} />
    </Routes>
  );
}
