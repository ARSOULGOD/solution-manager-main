/**
 * v1's real `AuthAdapter` (task 030, `FE-D8`, `FE-D14`, design §15).
 * Session lives in an httpOnly `SameSite=strict` cookie the GATEWAY sets —
 * this adapter never reads, writes or stores it. `credentials:
 * 'same-origin'` (task 026's `request()`) is what makes the cookie travel.
 *
 * CONTRACT: the login endpoint, its request/response shape, and the CSRF
 * mechanism (§16 Q3) are specified in `docs/design/frontend/backend-integration-auth-hierarchy.md`.
 */
import { configureCsrf as defaultConfigureCsrf, onUnauthorized, type PlaneClient } from '@shared/api';
import type { CsrfConfig } from '@shared/api/types';

import type {
  AuthAdapter,
  Credentials,
  Identity,
  SessionExpiredListener,
  Unsubscribe,
} from './AuthAdapter';

/**
 * One scoped permission entry as the backend returns it. `scopes` is the
 * `ScopePath[]` the contract declares — each entry an object whose own keys
 * name the scope levels it is held at. `{}` (no keys) means every scope:
 * the unscoped grant only Administrator binds (OR-12), and the signal
 * `superAdmin` below is derived from, since the real wire has no flag of
 * its own.
 */
interface WireEffectivePermission {
  permission: string;
  scopes: ReadonlyArray<Readonly<Record<string, unknown>>>;
}

interface WireOrganization {
  id: string;
  name: string;
}

/**
 * The real `/auth/identity` and `/auth/login` response shape
 * (`contracts/openapi/console.yaml`'s `Identity` schema), pre-flattening.
 * Confirmed live against the real backend 2026-10-08
 * (`docs/plans/integration/reports/real-e2e-2026-10-08.md`) — this
 * interface previously declared `role`, `superAdmin` and `organizationId`
 * as wire fields and an uppercase `source` enum, none of which the real
 * response carries; `source` is lowercase.
 */
interface WireIdentity {
  principalId: string;
  username: string;
  source: 'local' | 'idp';
  mustRotate: boolean;
  permissions: WireEffectivePermission[];
  csrfToken: string;
  organizations: WireOrganization[];
}

function isUnscoped(scopes: WireEffectivePermission['scopes']): boolean {
  return scopes.some((scope) => Object.keys(scope).length === 0);
}

function toIdentity(wire: WireIdentity): Identity {
  return {
    username: wire.username,
    source: wire.source,
    mustRotate: wire.mustRotate,
    superAdmin: wire.permissions.some((entry) => isUnscoped(entry.scopes)),
    organizationNames: wire.organizations.map((org) => org.name),
    permissions: wire.permissions.map((entry) => entry.permission),
  };
}

export function createLocalSessionAdapter(
  cpClient: PlaneClient,
  configureCsrf: (config: CsrfConfig) => void = defaultConfigureCsrf,
): AuthAdapter {
  let csrfToken: string | undefined;

  const registerCsrf = (): void => configureCsrf({ headerName: 'X-SM-CSRF', getToken: () => csrfToken });

  // Re-registered on every token received, not just at construction: the hook is process-global,
  // and React StrictMode double-invokes the `useMemo` that builds this adapter, so a discarded twin
  // constructed after the live one would otherwise own the hook while the live adapter's token
  // goes unsent (observed as a 403 CSRF_REJECTED on rotateCredential).
  function rememberCsrf(identity: WireIdentity): void {
    csrfToken = identity.csrfToken;
    registerCsrf();
  }

  registerCsrf();

  return {
    async getIdentity(): Promise<Identity> {
      const response = await cpClient.get<WireIdentity>('/auth/identity');
      rememberCsrf(response);
      return toIdentity(response);
    },

    async login(credentials: Credentials): Promise<Identity> {
      const response = await cpClient.post<WireIdentity>('/auth/login', {
        username: credentials.username,
        credential: credentials.password,
      });
      rememberCsrf(response);
      return toIdentity(response);
    },

    async logout(): Promise<void> {
      await cpClient.post('/auth/logout');
      csrfToken = undefined;
    },

    async rotateCredential(current: string, next: string): Promise<void> {
      await cpClient.post('/auth/credential', { currentCredential: current, newCredential: next });
    },

    onSessionExpired(callback: SessionExpiredListener): Unsubscribe {
      return onUnauthorized(callback);
    },

    getAccessToken(): undefined {
      return undefined;
    },
  };
}
