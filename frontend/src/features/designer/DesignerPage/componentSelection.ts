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
 *
 * **`FE-D55` — an instance may bind to its component types.** A package that
 * declares several components an instance does NOT all compose (File Mesh: one
 * component per role, and an instance is a source OR a sink, never both) needs
 * a way to say which. `componentTypes` is that, and it is deliberately the
 * smallest possible contract: absent means "no binding declared", and the
 * behaviour above is unchanged, so every package that has never heard of
 * bindings renders exactly as before. How a binding is AUTHORED is still §16 Q10
 * — this only defines how the Designer CONSUMES one.
 */
import { inVersion } from '@contracts/view-model/project';
import type { ComponentSelection, PackageVersionId, SolutionPackage } from '@contracts/view-model/types';

export function deriveComponentSelection(
  pkg: SolutionPackage,
  packageVersion: PackageVersionId,
  /**
   * The component types this instance composes (`FE-D55`). `undefined` means
   * the instance declares no binding and every version-eligible component
   * renders. `[]` is a binding that selects nothing — a different statement,
   * and one that must not be collapsed into "absent". Order comes from the
   * PACKAGE, never from this list, so the form's section order cannot be
   * changed by how a binding happens to be written.
   */
  componentTypes?: readonly string[],
  instanceRole?: string | null,
): ComponentSelection {
  const bound = componentTypes === undefined ? undefined : new Set(componentTypes);
  const role = instanceRole?.trim();
  return pkg.components
    .filter((component) => inVersion(component.version, packageVersion))
    .filter((component) => {
      if (!role) return true;
      const componentRole = component.role?.trim();
      return !componentRole || componentRole === 'none' || componentRole === role;
    })
    .filter((component) => bound === undefined || bound.has(component.type))
    .map((component) => ({
      type: component.type,
      instanceId: component.type,
      ...(component.role === undefined ? {} : { role: component.role }),
    }));
}
