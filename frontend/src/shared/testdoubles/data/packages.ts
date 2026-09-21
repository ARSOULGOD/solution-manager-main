/**
 * The single place that maps a solution id to the `SolutionPackage` its
 * instances render the Designer from, and an instance to the `ObservedState`
 * `project()` needs (task 122, `FE-D33`). Both the default fixture handler
 * (`handlers/dp.ts`) and the dev-loaded-package handler
 * (`app/dev/devPackageHandlers.ts`) read this, so there is one version-
 * resolution / observed-state rule in the fixture layer, not two.
 *
 * `shared -> contracts` (`FE-D34`) is what makes importing a real package
 * file from here possible at all.
 */
import CACHE_DRAFT from '@contracts/packages/cache/draftValues.json';
import CACHE_PACKAGE from '@contracts/packages/cache/package.json';
import FILE_MESH_PACKAGE from '@contracts/packages/file-mesh/package.json';
import type { DraftValues, HealthKey, ObservedState, SolutionPackage } from '@contracts/view-model/types';

import type { InstanceRow } from './instances';

/** Keyed by `InstanceRow.solutionId` — never an id-prefix heuristic. */
const PACKAGES_BY_SOLUTION: Readonly<Record<string, SolutionPackage>> = {
  cache: CACHE_PACKAGE as SolutionPackage,
  'file-mesh': FILE_MESH_PACKAGE as SolutionPackage,
};

export function packageForSolution(solutionId: string): SolutionPackage | undefined {
  return PACKAGES_BY_SOLUTION[solutionId];
}

/**
 * `InstanceRow.healthRollup` carries one extra member the contract's
 * `HealthKey` does not — `'notfound'`, which defers to `notFoundReason` for
 * the specific condition. `project()` only passes health through into
 * `ViewStatus`, so the nearest truthful contract member is used rather than
 * widening the contract for a fixture affordance.
 */
function observedHealth(rollup: InstanceRow['healthRollup']): HealthKey {
  return rollup === 'notfound' ? 'not_responding' : rollup;
}

export function observedStateFor(instance: InstanceRow): ObservedState {
  return {
    health: observedHealth(instance.healthRollup),
    versionState: instance.version.state,
    configState: instance.configState,
    declaredVersion: instance.version.declared,
    observedVersion: instance.version.observed,
  };
}

/**
 * The configuration-of-record `useDesignerDraft`'s `initialDraft` seeds
 * from. Every Cache instance in this fixture set shares ONE baseline draft
 * (`cache/draftValues.json`) — no less faithful than the pre-`FE-122` world,
 * where every Cache instance's Designer read the same baked
 * `CACHE_VIEW_MODEL` regardless of which instance was open. A solution with
 * no baked draft (File Mesh, and any dev-dropped package) starts empty —
 * exactly the reading `viewModelFor.ts` documented and this supersedes.
 */
export function draftValuesFor(instance: InstanceRow): DraftValues {
  if (instance.solutionId === 'cache') {
    return { 'cache-engine': CACHE_DRAFT };
  }
  return {};
}
