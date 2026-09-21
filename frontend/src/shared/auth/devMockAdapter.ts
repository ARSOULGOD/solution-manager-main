/**
 * DEV-only mock session (task 030, `FE-D24`). Fixture identity mirrors the
 * UI reference's demo user (`docs/design/frontend/reference/app/data.jsx`
 * `CURRENT_USER`) so screens built against it match the reference.
 *
 * **Tree-shaking, not just a runtime guard.** `AuthProvider.tsx` selects
 * this adapter behind a STATIC `if (import.meta.env.DEV)` branch — Vite
 * inlines that flag as a literal at build time, so a production build's
 * dead-code elimination removes both the branch and the otherwise-unused
 * import of this module (this file has no side effects, so it is safely
 * tree-shakeable). Task 101 asserts the built bundle never contains it —
 * the guard below is defence in depth for the case something imports this
 * module directly, not the primary mechanism.
 */
import { PERMISSION_CATALOG } from '@shared/authz/catalog';
import { readE2EOverrides } from '@shared/testdoubles/e2eOverrides';

import type { AuthAdapter, Identity, Unsubscribe } from './AuthAdapter';

const MOCK_IDENTITY: Identity = {
  name: 'Devon Ramirez',
  email: 'devon.ramirez@acme.example',
  role: 'Operator',
  organization: 'Acme Financial · self-hosted',
  superAdmin: true,
  // The development administrator holds the complete initial D10 catalog.
  permissions: PERMISSION_CATALOG.map((permission) => permission.id),
};

/**
 * `FE-099` journey 6 needs to *see* a control omitted for a missing verb,
 * which the single super-admin identity above makes impossible. The
 * profile comes off the URL (`?e2e_grants=`), DEV only — see
 * `e2eOverrides.ts` for why the URL and not a hidden global.
 *
 * `limited` keeps the verbs but drops `superAdmin`: that is the state in
 * which a control is *disabled with a reason* rather than omitted, which
 * is the distinction the journey exists to prove. `all` is the default
 * identity unchanged.
 */
function identityForSession(): Identity {
  const { grants } = readE2EOverrides();
  if (grants === undefined || grants === 'all') {
    return MOCK_IDENTITY;
  }
  if (grants === 'none') {
    return { ...MOCK_IDENTITY, superAdmin: false, permissions: [] };
  }
  return { ...MOCK_IDENTITY, superAdmin: false };
}

export function createDevMockAdapter(): AuthAdapter {
  if (!import.meta.env.DEV) {
    // Defence in depth (see header) — refuses to activate outside DEV
    // rather than silently granting a session (`FE-D24`).
    throw new Error('devMockAdapter must never be constructed outside import.meta.env.DEV.');
  }

  // Resolved once per adapter, not per call: an identity that changed
  // between `getIdentity` and `login` would be a session that silently
  // re-grants itself, which is not a state any real deployment can be in.
  const identity = identityForSession();

  return {
    getIdentity(): Promise<Identity> {
      return Promise.resolve(identity);
    },
    login(): Promise<Identity> {
      return Promise.resolve(identity);
    },
    logout(): Promise<void> {
      return Promise.resolve();
    },
    onSessionExpired(): Unsubscribe {
      // The mock session never expires on its own — DEV work should not
      // be interrupted by a fabricated 401.
      return () => {};
    },
    getAccessToken(): string | undefined {
      return undefined;
    },
  };
}
