import { lazy, Suspense } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';

import { AuthPage } from '@/auth/AuthPage';
import { selectIsAuthenticated, useAuthStore } from '@/auth/authStore';
import { RequireAuth } from '@/auth/RequireAuth';
import { RequireRole } from '@/auth/RequireRole';
import { env } from '@/env';
import { DebugHud } from '@/observability/DebugHud';
import { useLoggerContext } from '@/observability/useLoggerContext';
import { LobbyPage } from '@/pages/LobbyPage';
import { NotFoundPage } from '@/pages/NotFoundPage';
import { PlayPage } from '@/pages/PlayPage';
import { useSessionRecovery } from '@/session/useSessionRecovery';
import { ToastContainer } from '@/ui/toast/ToastContainer';
import { useWalletErrorRecovery } from '@/wallet/errors';

// Admin tools are large and only reachable by ADMIN-role users; lazy-loading
// keeps them out of the player-facing initial bundle (Task 9.4).
const AdminPage = lazy(() =>
  import('@/pages/AdminPage').then((m) => ({ default: m.AdminPage })),
);

function RootRedirect(): JSX.Element {
  const authed = useAuthStore(selectIsAuthenticated);
  if (authed) return <Navigate to="/play" replace />;
  if (env.VITE_ENABLE_DEV_TOKEN) return <Navigate to="/auth" replace />;
  return <LobbyPage />;
}

function AuthRoute(): JSX.Element {
  const authed = useAuthStore(selectIsAuthenticated);
  if (!env.VITE_ENABLE_DEV_TOKEN) return <NotFoundPage />;
  if (authed) return <Navigate to="/play" replace />;
  return <AuthPage />;
}

export function App(): JSX.Element {
  useSessionRecovery();
  useWalletErrorRecovery();
  useLoggerContext();

  return (
    <>
      <ToastContainer />
      <Routes>
        <Route path="/" element={<RootRedirect />} />
        <Route path="/auth" element={<AuthRoute />} />
        <Route
          path="/play"
          element={
            <RequireAuth>
              <PlayPage />
            </RequireAuth>
          }
        />
        <Route
          path="/admin"
          element={
            <RequireRole requiredRole="ADMIN">
              <Suspense fallback={<p role="status">Loading admin tools…</p>}>
                <AdminPage />
              </Suspense>
            </RequireRole>
          }
        />
        <Route path="*" element={<NotFoundPage />} />
      </Routes>
      <DebugHud />
    </>
  );
}
