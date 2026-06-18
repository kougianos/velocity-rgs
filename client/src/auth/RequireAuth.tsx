import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';

import { selectIsAuthenticated, useAuthStore } from './authStore';

interface Props {
  children: ReactNode;
}

export function RequireAuth({ children }: Props): JSX.Element {
  const authed = useAuthStore(selectIsAuthenticated);
  const location = useLocation();
  if (!authed) {
    return <Navigate to="/auth" replace state={{ from: location.pathname }} />;
  }
  return <>{children}</>;
}
