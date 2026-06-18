import { useEffect, type ReactNode } from 'react';
import { Navigate } from 'react-router-dom';

import { pushToast } from '@/ui/toast/toastStore';

import { selectHasRole, selectIsAuthenticated, useAuthStore } from './authStore';

interface Props {
  requiredRole: string;
  children: ReactNode;
}

export function RequireRole({ requiredRole, children }: Props): JSX.Element {
  const authed = useAuthStore(selectIsAuthenticated);
  const hasRole = useAuthStore(selectHasRole(requiredRole));
  const allowed = authed && hasRole;

  useEffect(() => {
    if (authed && !hasRole) {
      pushToast('info', `${requiredRole} role required`, { ttlMs: 3000 });
    }
  }, [authed, hasRole, requiredRole]);

  if (!authed) return <Navigate to="/auth" replace />;
  if (!allowed) return <Navigate to="/play" replace />;
  return <>{children}</>;
}
