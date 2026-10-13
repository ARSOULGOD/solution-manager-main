/**
 * Scope-path helpers for the assignments screens (task 092). A scope is a
 * path of node names below the synthetic root, e.g.
 * `["Acme Financial", "Payments"]` (`D20`, `FE-D53`). Ported from the UI reference's `admin_data.jsx` (`pathStr` /
 * `isPrefix`). An assignment made at a scope grants **there and everywhere
 * below** — `isScopePrefix` is that "reaches" test, and it is also what
 * tells a `direct` grant (`scopePathsEqual`) from an `inherited` one.
 */
import { t } from '@shared/i18n';
import type { ScopeLevel, ScopeNode } from '@shared/testdoubles/data/rbac';

/**
 * The EMPTY path is the global scope — every organization — which is where
 * the installer-created Solace Super-admin sits (`D19`, `D20`). Rendering
 * it as the empty string would print a blank cell in
 * the assignments table, so the label is supplied here rather than left for
 * each of the four call sites to remember.
 */
export function scopePathString(path: readonly string[]): string {
  return path.length === 0 ? t('adminScopeAllOrganizations') : path.join(' / ');
}

export function scopePathsEqual(a: readonly string[], b: readonly string[]): boolean {
  return a.length === b.length && a.every((segment, index) => segment === b[index]);
}

/** True when `ancestor` is at or above `descendant` — i.e. an assignment at `ancestor` reaches `descendant`. */
export function isScopePrefix(ancestor: readonly string[], descendant: readonly string[]): boolean {
  return (
    ancestor.length <= descendant.length &&
    ancestor.every((segment, index) => segment === descendant[index])
  );
}

/**
 * The hierarchy level a scope path names (`D20`, `FE-D53`). DERIVED from the
 * path's length rather than stored beside it, so a path and its declared
 * level can never disagree:
 *
 * ```text
 * []                                  → all organizations
 * ['Acme Financial']                         → Organization
 * ['Acme Financial','Payments']              → Sub-organization
 * ['Acme Financial','Payments','PROD']       → Environment
 * ['Acme Financial','Payments','PROD','cache'] → Instance
 * ```
 *
 * Organization is the first real segment and the server-derived isolation
 * boundary.
 */
export function scopeLevelOf(path: readonly string[]): ScopeLevel {
  switch (path.length) {
    case 0:
      return 'all';
    case 1:
      return 'organization';
    case 2:
      return 'subOrganization';
    case 3:
      return 'environment';
    default:
      return 'instance';
  }
}

/** The localized name of a scope level — used wherever a grant's origin is explained. */
export function scopeLevelLabel(level: ScopeLevel): string {
  switch (level) {
    case 'all':
      return t('adminScopeLevelAll');
    case 'organization':
      return t('adminScopeLevelOrganization');
    case 'subOrganization':
      return t('adminScopeLevelSubOrganization');
    case 'environment':
      return t('adminScopeLevelEnvironment');
    default:
      return t('adminScopeLevelInstance');
  }
}

/** The organization a scope path belongs to — `undefined` for the global scope. */
export function organizationOf(path: readonly string[]): string | undefined {
  return path[0];
}

/**
 * The children of the node at `path`, or `[]` when the path names nothing
 * in this tree. The dependent Organization → Sub-organization →
 * Environment selectors are built from this and nothing else, which is
 * what makes an invalid cross-organization combination unrepresentable
 * rather than merely rejected.
 */
export function childNamesAt(root: ScopeNode, path: readonly string[]): string[] {
  let node: ScopeNode | undefined = root;
  for (const segment of path) {
    node = node?.children?.find((child) => child.name === segment);
  }
  return (node?.children ?? []).map((child) => child.name);
}
