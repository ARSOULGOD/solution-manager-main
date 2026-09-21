/**
 * Route guard (task 031, `FE-D24`, design §14, §15). Wraps `AppShell` in
 * `AuthenticatedLayout.tsx` (task 036 wiring), gating every authenticated
 * screen; `idleTimeoutMinutes` comes from `RuntimeConfig`, which
 * `shared/*` may not import directly — the app-layer call site supplies it.
 *
 * Two distinguishable arrivals at `/login`, both driven by `AuthProvider`'s
 * `status`/`arrival` state:
 * - **`arrival=session`** — a 401 on any request (`AuthProvider` already
 *   sets this on `onSessionExpired`).
 * - **`arrival=idle`** — this component's own idle timer expires and calls
 *   `logout('idle')`.
 */
import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router';

import { useAuth } from './AuthProvider';
import { useIdleTimer } from './useIdleTimer';

export interface RequireAuthProps {
  children: ReactNode;
  /** From `config.json` (`RuntimeConfig.idleTimeoutMinutes`, default 30) — supplied by the app layer, never read here. */
  idleTimeoutMinutes: number;
  loginPath?: string;
}

export function RequireAuth({
  children,
  idleTimeoutMinutes,
  loginPath = '/login',
}: RequireAuthProps) {
  const { status, arrival, logout } = useAuth();
  const location = useLocation();

  // Disabled (0) until authenticated — no point idling out a visitor who
  // is already at the login screen.
  useIdleTimer(status === 'authenticated' ? idleTimeoutMinutes : 0, () => {
    void logout('idle');
  });

  if (status === 'loading') {
    // Task 036's app shell supersedes this with a real skeleton.
    return null;
  }

  if (status === 'unauthenticated') {
    const params = new URLSearchParams();
    if (arrival !== null) {
      params.set('arrival', arrival);
    }
    const attempted = `${location.pathname}${location.search}`;
    if (attempted !== '/') {
      params.set('from', attempted);
    }
    const search = params.toString();
    return <Navigate to={search.length > 0 ? `${loginPath}?${search}` : loginPath} replace />;
  }

  return <>{children}</>;
}
