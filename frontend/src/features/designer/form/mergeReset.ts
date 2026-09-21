/**
 * `FE-D17`'s reset-by-merge, split out from `useDesignerDraft` so it stays a
 * pure function over `DraftValues` — no RHF, no React (task FE-052).
 */
import { inVersion } from '@contracts/view-model/project';
import type {
  ComponentSelection,
  ComponentValues,
  DraftValues,
  FieldValue,
  PackageVersionId,
  SolutionPackage,
} from '@contracts/view-model/types';

/**
 * Fills in each selected component instance's own field `default`s over a
 * raw draft — the same rule `project()`'s own `applyDefaults`
 * (`contracts/view-model/project.ts`) evaluates `when:` against
 * (obligation 1), reproduced here because a discriminator switch needs
 * this as a real draft value RHF holds, not only as `project()`'s
 * read-only defaults-applied view. Never overwrites a key the draft
 * already has — only fills a gap, so an inactive branch's own retained
 * value is untouched.
 *
 * `packageVersion` is filtered through the contract's own `inVersion()`
 * — never a locally restated rule (`FE-D37`) — and, exactly as
 * `project()`'s own `applyDefaults` does, a field excluded by version
 * contributes no default at all.
 */
export function applyPackageDefaults(
  pkg: SolutionPackage,
  componentSelection: ComponentSelection,
  draft: DraftValues,
  packageVersion: PackageVersionId,
): DraftValues {
  const next: Record<string, ComponentValues> = { ...draft };
  for (const selection of componentSelection) {
    const component = pkg.components.find((candidate) => candidate.type === selection.type);
    if (component === undefined || !inVersion(component.version, packageVersion)) {
      continue;
    }
    const resolved: Record<string, FieldValue> = { ...(draft[selection.instanceId] ?? {}) };
    for (const field of component.fields) {
      if (!inVersion(field.version, packageVersion)) {
        continue;
      }
      if (!(field.key in resolved) && field.default !== undefined) {
        resolved[field.key] = field.default;
      }
    }
    next[selection.instanceId] = resolved;
  }
  return next;
}

/**
 * `current` merged with `next`, per component instance, key by key —
 * anything `next` doesn't mention (an inactive discriminator branch's
 * already-typed values) survives untouched. A plain `form.reset(next)`
 * would drop those keys instead (`UI-D4` retention) — the defect `FE-D17`
 * names this function to prevent.
 */
export function mergeReset(current: DraftValues, next: DraftValues): DraftValues {
  const instanceIds = new Set([...Object.keys(current), ...Object.keys(next)]);
  const merged: Record<string, ComponentValues> = {};
  for (const instanceId of instanceIds) {
    merged[instanceId] = { ...(current[instanceId] ?? {}), ...(next[instanceId] ?? {}) };
  }
  return merged;
}
