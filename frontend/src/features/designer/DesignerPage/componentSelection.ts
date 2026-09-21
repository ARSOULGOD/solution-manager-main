/**
 * `deriveComponentSelection` (task 123, `FE-D35`) — the single source of
 * truth for which component instances a `DesignerPage` renders. Filters
 * the package's own components by the instance's version (`FE-D37`, never
 * a locally restated rule) so a component excluded at this version
 * contributes nothing — not a section, not a field.
 *
 * ⚠️ **`one-of-many` composition is not invented.** `UI-D1` says a package
 * declares component TYPES and an instance composes named instances of
 * them, but nothing in this console produces that composition data —
 * `FE-115`'s declare form asks for identity only, and §16 Q10 (the declare
 * contract) is still open. This returns the degenerate `exactly-one` case:
 * one entry per declared component, `instanceId = type`. A component
 * declaring `cardinality: 'one-of-many'` therefore renders once. The limit
 * is stated here, in the one function that owns it, rather than left to be
 * discovered as a silent gap.
 */
import { inVersion } from '@contracts/view-model/project';
import type { ComponentSelection, PackageVersionId, SolutionPackage } from '@contracts/view-model/types';

export function deriveComponentSelection(
  pkg: SolutionPackage,
  packageVersion: PackageVersionId,
): ComponentSelection {
  return pkg.components
    .filter((component) => inVersion(component.version, packageVersion))
    .map((component) => ({
      type: component.type,
      instanceId: component.type,
      ...(component.role === undefined ? {} : { role: component.role }),
    }));
}
