import { useMemo } from 'react';
import { useParams } from 'react-router';

import { createInstancesRepository, InstancesPage } from '@features/inventory/instances';
import { createSolutionsRepository } from '@features/inventory/solutions';
import { createCpClient } from '@shared/api/cpClient';
import { createDpClient } from '@shared/api/dpClient';
import { createHierarchyRepository } from '@shared/hierarchy';

import { useRuntimeConfig } from '../boot/RuntimeConfig';
import { NotFound } from '../NotFound';

// Repository construction lives here, not in the feature, for the same
// reason `HomeRoute.tsx`/`SolutionsRoute.tsx` document: `RuntimeConfig` is
// an app-layer concern a feature may not import directly (boundaries).
export default function InstancesRoute() {
  const { solutionId } = useParams<{ solutionId: string }>();
  const config = useRuntimeConfig();
  const instancesRepository = useMemo(
    () => createInstancesRepository(createDpClient(config.apiBasePath), createCpClient(config.apiBasePath)),
    [config.apiBasePath],
  );
  const solutionsRepository = useMemo(
    () => createSolutionsRepository(createCpClient(config.apiBasePath)),
    [config.apiBasePath],
  );
  // Control-plane, like solutions — the hierarchy is catalog, not telemetry.
  const hierarchyRepository = useMemo(
    () => createHierarchyRepository(createCpClient(config.apiBasePath)),
    [config.apiBasePath],
  );

  if (solutionId === undefined) {
    return <NotFound />;
  }

  return (
    <InstancesPage
      solutionId={solutionId}
      repository={instancesRepository}
      solutionsRepository={solutionsRepository}
      hierarchyRepository={hierarchyRepository}
      sshDockerEnabled={config.channel === 'local-demo'}
    />
  );
}
