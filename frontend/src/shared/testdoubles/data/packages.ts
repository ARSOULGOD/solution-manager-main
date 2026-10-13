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
import { toFrontendPackage } from '@contracts/solution-package';
import type { CanonicalSolutionPackage } from '@contracts/solution-package/types';
import type { DraftValues, HealthKey, ObservedState, SolutionPackage } from '@contracts/view-model/types';

import type { InstanceRow } from './instances';

/**
 * Keyed by `InstanceRow.solutionId` — never an id-prefix heuristic.
 *
 * Bundled packages are authored in the CANONICAL dialect (the one
 * `solution-package.schema.json` defines and `validate-package.py` gates),
 * so they go through the same normalizer the dev-upload path uses
 * (`app/dev/parsePackage.ts`) rather than a raw cast. The engine reads
 * `label`/`helperText`/`advanced` FLAT but `section`/`order`/`readOnly`
 * NESTED, and a raw cast satisfies the compiler while silently dropping
 * every authored label — which is exactly what `file-mesh` was doing
 * (task FE-149, design §14.1). One conversion, one place.
 */
const PACKAGES_BY_SOLUTION: Readonly<Record<string, SolutionPackage>> = {
  cache: toFrontendPackage(CACHE_PACKAGE as unknown as CanonicalSolutionPackage),
  'file-mesh': toFrontendPackage(FILE_MESH_PACKAGE as unknown as CanonicalSolutionPackage),
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

/**
 * A declared instance's chosen connections (`InstanceRow.connections`,
 * `UI-D27`), reshaped into `DraftValues` — one entry per component that
 * actually declares the selector field, keyed the same way
 * `deriveComponentSelection` keys every other component (`instanceId =
 * component.type`). Generic: it reads which field keys each component
 * declares and nothing else, so it carries no knowledge of what a
 * `solaceBroker` selector is, or that Cache is the package that has one.
 *
 * A selector key the package does not declare contributes nothing — this
 * only ever writes SELECTIONS the package itself asked for.
 */
export function connectionSelectorValues(
  pkg: SolutionPackage,
  connections: Readonly<Record<string, string>>,
): DraftValues {
  const values: Record<string, Record<string, string>> = {};
  for (const component of pkg.components) {
    const fieldKeys = new Set(component.fields.map((field) => field.key));
    const selected = Object.entries(connections).filter(([key]) => fieldKeys.has(key));
    if (selected.length > 0) {
      values[component.type] = Object.fromEntries(selected);
    }
  }
  return values;
}
