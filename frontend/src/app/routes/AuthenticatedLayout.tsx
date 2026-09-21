import { useMemo } from 'react';

import { createSolutionsRepository } from '@features/inventory/solutions';
import { createCpClient } from '@shared/api/cpClient';
import { RequireAuth } from '@shared/auth/RequireAuth';
import { readE2EOverrides } from '@shared/testdoubles/e2eOverrides';

import { useRuntimeConfig } from '../boot/RuntimeConfig';
import { AppShell } from '../shell/AppShell';

/**
 * `FE-099` journey 7 has to watch the idle timer actually fire, and
 * `config.json` ships `idleTimeoutMinutes: 30`. The override is read here
 * rather than in `RequireAuth` for the same reason the real value is:
 * this is the app layer, and `RequireAuth` may not read deploy-time
 * config itself. DEV only — `readE2EOverrides` returns nothing otherwise,
 * so a production build keeps `config.idleTimeoutMinutes` untouched.
 */
function idleTimeoutFor(configuredMinutes: number): number {
  // The guard is HERE, at the call site, not only inside
  // `readE2EOverrides`. Both are correct at runtime, but only this shape
  // gets eliminated: Vite inlines `import.meta.env.DEV` as a literal
  // `false` in a production build, folds this branch away and drops the
  // import with it. With the check in the callee alone, the call survives
  // — verified against a real `npm run build`, where the override reader
  // shipped as a stub returning `undefined`. Same rule
  // `devPackageHandlers.ts` documents (`FE-D24`), and what `FE-101` will
  // assert.
  if (!import.meta.env.DEV) {
    return configuredMinutes;
  }
  const { idleSeconds } = readE2EOverrides();
  return idleSeconds === undefined ? configuredMinutes : idleSeconds / 60;
}

/**
 * Pathless layout route (task 036 wiring) gating every authenticated
 * screen behind `RequireAuth` and mounting `AppShell` around their
 * `<Outlet>`. `idleTimeoutMinutes` is the same app-layer RuntimeConfig
 * value `LoginRoute.tsx` documents for itself — `RequireAuth` may not
 * read it directly (boundaries).
 *
 * The solutions repository is built here for the same reason
 * `SolutionsRoute.tsx` builds its own: `RuntimeConfig` is app-layer, and
 * `app/shell` is not where deploy-time config is read. Both repositories
 * feed the one `queryKeys.solutions.list()` cache entry, so the drawer
 * and the catalog screen share a single request.
 */
export default function AuthenticatedLayout() {
  const config = useRuntimeConfig();
  const idleTimeoutMinutes = idleTimeoutFor(config.idleTimeoutMinutes);
  const solutionsRepository = useMemo(
    () => createSolutionsRepository(createCpClient(config.apiBasePath)),
    [config.apiBasePath],
  );

  return (
    <RequireAuth idleTimeoutMinutes={idleTimeoutMinutes}>
      <AppShell solutionsRepository={solutionsRepository} />
    </RequireAuth>
  );
}
