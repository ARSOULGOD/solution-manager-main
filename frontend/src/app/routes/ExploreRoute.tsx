import { useMemo } from 'react';
import { useParams } from 'react-router';

import { ExplorePage, MetricsProvider } from '@features/monitoring';
import { createConsoleClient } from '@shared/api/consoleClient';

import { useRuntimeConfig } from '../boot/RuntimeConfig';
import { NotFound } from '../NotFound';

// Real page (task 077). Same undefined-check-then-NotFound shape
// `InstanceCategoryRoute.tsx` uses — react-router types matched params as
// possibly-undefined even though this route pattern guarantees both are
// present once matched.
//
// `MetricsProvider` mount (task 078): `ExplorePage` didn't call
// `useMetrics()`/`useCatalog` before this task, so nothing needed to
// mount it. Constructed here, not in the feature, for the same reason
// `InstanceCategoryRoute.tsx` documents for its own repositories:
// `RuntimeConfig` is an app-layer concern a feature may not import
// directly (boundaries, task 009).
export default function ExploreRoute() {
  const { solutionId, instanceId } = useParams<{ solutionId: string; instanceId: string }>();
  const config = useRuntimeConfig();
  const consoleClient = useMemo(
    () => createConsoleClient(config.apiBasePath),
    [config.apiBasePath],
  );

  if (solutionId === undefined || instanceId === undefined) {
    return <NotFound />;
  }

  return (
    <MetricsProvider consoleClient={consoleClient}>
      <ExplorePage solutionId={solutionId} instanceId={instanceId} />
    </MetricsProvider>
  );
}
