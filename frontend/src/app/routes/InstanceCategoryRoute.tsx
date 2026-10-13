import { useMemo } from 'react';
import { useParams } from 'react-router';

import { createConnectionsRepository } from '@features/connections';
import {
  createActionsRepository,
  createApplyRepository,
  createConfigRepository,
} from '@features/designer';
import { createGovernanceRepository } from '@features/governance';
import { InstanceShell } from '@features/instance';
import { createInstancesRepository } from '@features/inventory/instances';
import { createSolutionsRepository } from '@features/inventory/solutions';
import { createConsoleClient } from '@shared/api/consoleClient';
import { createCpClient } from '@shared/api/cpClient';
import { createDpClient } from '@shared/api/dpClient';
import { createHierarchyRepository } from '@shared/hierarchy';

import { useRuntimeConfig } from '../boot/RuntimeConfig';
import { NotFound } from '../NotFound';
import { isInstanceCategory } from '../routes';

// :category is validated here, not by the route pattern — an unknown
// value renders NotFound in place rather than falling through to one of
// the three real categories (design §14: "an unknown value 404s rather
// than falling through"). Real content per category is Phase 4/5/6 —
// task 044 ships the shared header/tabs shell only (see `InstanceShell`).
//
// Repository construction lives here, not in the feature, for the same
// reason `InstancesRoute.tsx` documents: `RuntimeConfig` is an app-layer
// concern a feature may not import directly (boundaries).
export default function InstanceCategoryRoute() {
  const { solutionId, instanceId, category } = useParams<{
    solutionId: string;
    instanceId: string;
    category: string;
  }>();
  const config = useRuntimeConfig();
  const {
    instancesRepository,
    configRepository,
    applyRepository,
    actionsRepository,
    governanceRepository,
    hierarchyRepository,
    consoleClient,
  } = useMemo(() => {
    const dpClient = createDpClient(config.apiBasePath);
    // `configRepository.loadDesigner` (plan 008 Task 1) needs the raw package too
    // (`getSolutionPackage`, D-4) — reused from `solutionsRepository` rather than
    // duplicated, through dependency injection rather than a cross-feature import
    // (`eslint-plugin-boundaries`: a feature reaches another only through its barrel).
    const solutions = createSolutionsRepository(createCpClient(config.apiBasePath));
    const connections = createConnectionsRepository(createCpClient(config.apiBasePath));
    return {
      instancesRepository: createInstancesRepository(dpClient),
      configRepository: createConfigRepository(
        dpClient,
        solutions.getPackage.bind(solutions),
        solutionId,
        connections.list.bind(connections),
      ),
      applyRepository: createApplyRepository(dpClient),
      actionsRepository: createActionsRepository(dpClient),
      governanceRepository: createGovernanceRepository(dpClient),
      // `InstanceHeader`'s EnvChip resolves `Instance.environmentId` to a
      // name through this (plan 007) — `cpClient`-based like plan 001's
      // own hierarchy cascade, not `dpClient`.
      hierarchyRepository: createHierarchyRepository(createCpClient(config.apiBasePath)),
      // Monitoring's own plane (task 085) — built here for the same
      // boundary reason as the `dpClient`-derived repositories above.
      consoleClient: createConsoleClient(config.apiBasePath),
    };
  }, [config.apiBasePath, solutionId]);

  if (
    solutionId === undefined ||
    instanceId === undefined ||
    category === undefined ||
    !isInstanceCategory(category)
  ) {
    return <NotFound />;
  }

  return (
    <InstanceShell
      solutionId={solutionId}
      instanceId={instanceId}
      category={category}
      repository={instancesRepository}
      configRepository={configRepository}
      applyRepository={applyRepository}
      actionsRepository={actionsRepository}
      governanceRepository={governanceRepository}
      hierarchyRepository={hierarchyRepository}
      consoleClient={consoleClient}
    />
  );
}
