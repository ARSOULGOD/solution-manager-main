import { useMemo } from 'react';

import { createTargetsRepository, TargetsPreview } from '@features/targets';
import { createCpClient } from '@shared/api/cpClient';

import { useRuntimeConfig } from '../boot/RuntimeConfig';

// Repository construction lives here, not in the feature, for the same
// reason `AdminRoute.tsx` documents: `RuntimeConfig` is an app-layer
// concern a feature may not import directly (boundaries). Not in v1 nav —
// reachable via `AccountMenu`'s DEV-only preview link, same as `/admin`.
export default function TargetsRoute() {
  const config = useRuntimeConfig();
  const repository = useMemo(
    () => createTargetsRepository(createCpClient(config.apiBasePath)),
    [config.apiBasePath],
  );

  return <TargetsPreview repository={repository} />;
}
