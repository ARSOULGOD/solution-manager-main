/**
 * The session context every feature reads (task 030, `FE-D8`, design §15).
 * Selects `devMockAdapter` under `import.meta.env.DEV`, `localSessionAdapter`
 * otherwise — see `devMockAdapter.ts`'s header for why that static branch is
 * what makes the mock tree-shakeable out of a production build.
 *
 * Exposes `arrival` so task 031's `RequireAuth`/login route can distinguish
 * *"the server no longer recognised your session"* from an idle-timeout
 * sign-out — both call `logout()`, just with a different reason.
 */
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';

import type { PlaneClient } from '@shared/api';

import type { AuthAdapter, Credentials, Identity } from './AuthAdapter';
import { createDevMockAdapter } from './devMockAdapter';
import { createLocalSessionAdapter } from './localSessionAdapter';

export type AuthStatus = 'loading' | 'authenticated' | 'unauthenticated';
export type ArrivalReason = 'session' | 'idle' | null;

export interface AuthContextValue {
  status: AuthStatus;
  identity: Identity | null;
  arrival: ArrivalReason;
  // Arrow-typed (not method shorthand) so destructuring these out of the
  // context value — the normal way to consume it — never trips
  // @typescript-eslint/unbound-method.
  login: (credentials: Credentials) => Promise<Identity>;
  logout: (reason?: ArrivalReason) => Promise<void>;
  getAccessToken: () => string | undefined;
}

const AuthContext = createContext<AuthContextValue | null>(null);

function selectAdapter(cpClient: PlaneClient): AuthAdapter {
  // Static branch, deliberately not a runtime toggle — see devMockAdapter.ts.
  if (import.meta.env.DEV) {
    return createDevMockAdapter();
  }
  return createLocalSessionAdapter(cpClient);
}

export interface AuthProviderProps {
  cpClient: PlaneClient;
  children: ReactNode;
  /**
   * Test-only seam. Vitest always runs with `import.meta.env.DEV === true`,
   * so `selectAdapter` always picks `devMockAdapter` — which never expires
   * a session by design — leaving the 401 → unauthenticated wiring below
   * otherwise untestable through this component. Never set in application
   * code; production always goes through `selectAdapter`.
   */
  adapterOverride?: AuthAdapter;
}

export function AuthProvider({ cpClient, children, adapterOverride }: AuthProviderProps) {
  const adapter = useMemo(
    () => adapterOverride ?? selectAdapter(cpClient),
    [cpClient, adapterOverride],
  );

  const [status, setStatus] = useState<AuthStatus>('loading');
  const [identity, setIdentity] = useState<Identity | null>(null);
  const [arrival, setArrival] = useState<ArrivalReason>(null);

  useEffect(() => {
    let cancelled = false;

    adapter
      .getIdentity()
      .then((resolved) => {
        if (!cancelled) {
          setIdentity(resolved);
          setStatus('authenticated');
        }
      })
      .catch(() => {
        if (!cancelled) {
          setIdentity(null);
          setStatus('unauthenticated');
        }
      });

    const unsubscribe = adapter.onSessionExpired(() => {
      setIdentity(null);
      setStatus('unauthenticated');
      setArrival('session');
    });

    return () => {
      cancelled = true;
      unsubscribe();
    };
  }, [adapter]);

  const login = useCallback(
    async (credentials: Credentials): Promise<Identity> => {
      const resolved = await adapter.login(credentials);
      setIdentity(resolved);
      setStatus('authenticated');
      setArrival(null);
      return resolved;
    },
    [adapter],
  );

  const logout = useCallback(
    async (reason: ArrivalReason = null): Promise<void> => {
      await adapter.logout();
      setIdentity(null);
      setStatus('unauthenticated');
      setArrival(reason);
    },
    [adapter],
  );

  const getAccessToken = useCallback((): string | undefined => {
    return adapter.getAccessToken?.();
  }, [adapter]);

  const value = useMemo<AuthContextValue>(
    () => ({ status, identity, arrival, login, logout, getAccessToken }),
    [status, identity, arrival, login, logout, getAccessToken],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (value === null) {
    throw new Error('useAuth() called outside an AuthProvider.');
  }
  return value;
}
