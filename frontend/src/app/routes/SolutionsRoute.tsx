import { useEffect, useMemo, useState } from 'react';

import {
  createSolutionsRepository,
  SolutionsPage,
  type SolutionsRepository,
} from '@features/inventory/solutions';
import { createCpClient } from '@shared/api/cpClient';

import { useRuntimeConfig } from '../boot/RuntimeConfig';

// Repository construction lives here, not in the feature, for the same
// reason `HomeRoute.tsx` documents: `RuntimeConfig` is an app-layer
// concern a feature may not import directly (boundaries).
export default function SolutionsRoute() {
  const config = useRuntimeConfig();
  const base = useMemo(
    () => createSolutionsRepository(createCpClient(config.apiBasePath)),
    [config.apiBasePath],
  );

  // Fixture-only: wrap the repository so a dropped package file is actually
  // parsed (task FE-114). The wire contract is unchanged — the wrapper
  // delegates `load()` straight through and the bytes never leave the
  // browser; see `devSolutionsRepository.ts`.
  //
  // The `import()` sits textually inside a literal `if (import.meta.env.DEV)`
  // for the reason `gallery.routes.ts` documents at length: Vite replaces
  // the check with `false` per-module in a production build and the
  // bundler then eliminates the branch *and the dynamic import inside it*,
  // so no dev-package chunk is emitted. Routing the same check through a
  // variable or a helper defeats that constant-folding.
  //
  // Swapping the repository in asynchronously is safe: it changes only the
  // `load`/`commitPending`/`discardPending` path, which nothing can reach
  // until the operator opens the load dialog. The local demo is a live
  // backend channel, so it must not take this fixture wrapper merely because
  // Vite itself is running in development mode.
  const [decorate, setDecorate] = useState<
    ((repository: SolutionsRepository) => SolutionsRepository) | null
  >(null);
  useEffect(() => {
    if (import.meta.env.DEV && config.channel !== 'local-demo') {
      let current = true;
      void import('../dev/devSolutionsRepository').then(({ withDevPackages }) => {
        if (current) {
          // Wrapped: `useState`'s setter treats a bare function as an updater.
          setDecorate(() => withDevPackages);
        }
      });
      return () => {
        current = false;
      };
    }
    return undefined;
  }, [config.channel]);

  const repository = useMemo(() => (decorate === null ? base : decorate(base)), [base, decorate]);

  return <SolutionsPage repository={repository} />;
}
