import { useMemo } from 'react';
import { useParams } from 'react-router';

import { DashboardView, MetricsProvider } from '@features/monitoring';
import { createConsoleClient } from '@shared/api/consoleClient';

import { useRuntimeConfig } from '../boot/RuntimeConfig';
import { NotFound } from '../NotFound';

// Real page (task 085). Same shape as `ExploreRoute.tsx`: `MetricsProvider`
// is constructed here, not in the feature, because `RuntimeConfig` is an
// app-layer concern a feature may not import directly (boundaries).
//
// `dashboardId` is taken verbatim from the URL, not recomputed from
// `defaultDashboardId(instanceId)` — the operator got here via a link that
// already encoded the id (today, always that same deterministic one; see
// `dashboardRepository.ts`), and trusting the param is what lets a future
// second dashboard id work here without touching this route.
export default function DashboardRoute() {
  const { solutionId, instanceId, dashboardId } = useParams<{
    solutionId: string;
    instanceId: string;
    dashboardId: string;
  }>();
  const config = useRuntimeConfig();
  const consoleClient = useMemo(
    () => createConsoleClient(config.apiBasePath),
    [config.apiBasePath],
  );

  if (solutionId === undefined || instanceId === undefined || dashboardId === undefined) {
    return <NotFound />;
  }

  return (
    <MetricsProvider consoleClient={consoleClient}>
      <DashboardView
        solutionId={solutionId}
        instanceId={instanceId}
        dashboardId={dashboardId}
        landing={false}
      />
    </MetricsProvider>
  );
}
