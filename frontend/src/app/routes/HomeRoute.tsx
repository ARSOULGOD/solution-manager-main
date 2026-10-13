import { useMemo } from 'react';

import { createHomeRepository, HomePage } from '@features/home';
import { createCpClient } from '@shared/api/cpClient';
import { createDpClient } from '@shared/api/dpClient';

import { useRuntimeConfig } from '../boot/RuntimeConfig';

// `apiBasePath` is an app-layer concern (RuntimeConfig) that a feature may
// not import directly (boundaries) — same constraint LoginRoute.tsx
// documents for itself. The repository is constructed here, at the one
// place that is allowed to know both RuntimeConfig and the feature.
//
// `dpClient` is needed alongside `cpClient` only because `HomeAttention`
// items carry no instance name/solutionId of their own (plan 004's D-1
// option B) — the repository resolves each one via `GET
// /dp/instances/{id}` to build a working link.
export default function HomeRoute() {
  const config = useRuntimeConfig();
  const repository = useMemo(
    () => createHomeRepository(createCpClient(config.apiBasePath), createDpClient(config.apiBasePath)),
    [config.apiBasePath],
  );

  return <HomePage repository={repository} />;
}
