import { useMemo } from 'react';
import { Navigate } from 'react-router';

import { createHomeRepository, HomePage } from '@features/home';
import { createCpClient } from '@shared/api/cpClient';

import { useRuntimeConfig } from '../boot/RuntimeConfig';

// `apiBasePath` is an app-layer concern (RuntimeConfig) that a feature may
// not import directly (boundaries) — same constraint LoginRoute.tsx
// documents for itself. The repository is constructed here, at the one
// place that is allowed to know both RuntimeConfig and the feature.
export default function HomeRoute() {
  const config = useRuntimeConfig();

  // The local demo starts with an empty solution catalog. The production
  // dashboard depends on summary endpoints that are intentionally outside
  // this narrow demo surface, so begin at the real onboarding flow instead.
  if (config.channel === 'local-demo') {
    return <Navigate replace to="/solutions" />;
  }

  const repository = useMemo(
    () => createHomeRepository(createCpClient(config.apiBasePath)),
    [config.apiBasePath],
  );

  return <HomePage repository={repository} />;
}
