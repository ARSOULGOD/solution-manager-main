import type { Instance } from '@shared/api/contracts/instances';

import { nodeId } from './hierarchy';
import type { InstanceRow } from './instances';

/**
 * The real-contract-shaped view of a fixture row (integration plan 007,
 * 2026-10-09), for the `listInstances`/`getInstance`/`declareInstance`
 * handlers only. Every other handler keys off the `InstanceRow` directly.
 * `environmentId` is `hierarchy.ts`'s own deterministic `nodeId(org,
 * subOrg, env)`, so `HierarchyRepository.environmentPath()` (which
 * traverses the real `/cp/hierarchy/*` handlers) resolves it back to the
 * same names a test asserts against.
 *
 * Shared by the default handlers (`handlers/dp.ts`) and the dev-session
 * handlers (`app/dev/devPackageHandlers.ts`), so both serve one wire shape.
 * The dev handlers once returned the raw `InstanceRow` (`id`, no
 * `instanceId`), and the instances grid, which keys rows on `instanceId`,
 * crashed on it.
 */
export function toRealInstance(row: InstanceRow): Instance {
  return {
    stateVersion: 1,
    instanceId: row.id,
    name: row.name,
    solutionId: row.solutionId,
    environmentId: nodeId(row.org, row.subOrg, row.env),
    declaredPackageVersion: row.packageVersion,
    acquisition: { mode: 'direct-http' },
    placements: row.replicas.map((replica, index) => ({
      placementId: `${row.id}-placement-${String(index)}`,
      targetId: `${row.id}-target`,
      containerName: replica.name,
      probeStrength: 'readiness',
    })),
  };
}
