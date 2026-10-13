/**
 * The authoring half of `FE-D55` (`FE-D56`, design §10.3). `role` is
 * already a closed-vocabulary field per component (`UI-D26`); this groups
 * a package's version-eligible components by role so the declare dialog
 * can offer a choice when — and only when — more than one distinct
 * non-`none` role exists. Nothing here names a solution, a role string, or
 * a component type (`REQ-001`); a future package under the same schema
 * needs no change here.
 *
 * `deriveComponentSelection` (`componentSelection.ts`) is this function's
 * downstream consumer, unchanged by this module: it still takes an
 * explicit `componentTypes` list and never sees `role`.
 */
import { inVersion } from '@contracts/view-model/project';
import type { ComponentDeclaration, PackageVersionId, SolutionPackage } from '@contracts/view-model/types';

export interface ComponentRoleGroup {
  role: string;
  componentTypes: readonly string[];
}

function eligible(pkg: SolutionPackage, packageVersion: PackageVersionId): readonly ComponentDeclaration[] {
  return pkg.components.filter((component) => inVersion(component.version, packageVersion));
}

/** One group per distinct non-empty role among this version's components, in first-seen order. */
export function deriveComponentRoleGroups(
  pkg: SolutionPackage,
  packageVersion: PackageVersionId,
): readonly ComponentRoleGroup[] {
  const byRole = new Map<string, string[]>();
  for (const component of eligible(pkg, packageVersion)) {
    if (component.role === undefined || component.role === null || component.role.trim() === '' || component.role === 'none') continue;
    const types = byRole.get(component.role) ?? [];
    types.push(component.type);
    byRole.set(component.role, types);
  }
  return [...byRole.entries()].map(([role, componentTypes]) => ({ role, componentTypes }));
}

/** Version-eligible component types with no role, an empty role, or legacy `role: 'none'` — always shared. */
export function sharedComponentTypes(pkg: SolutionPackage, packageVersion: PackageVersionId): readonly string[] {
  return eligible(pkg, packageVersion)
    .filter((component) =>
      component.role === undefined || component.role === null || component.role.trim() === '' || component.role === 'none',
    )
    .map((component) => component.type);
}
