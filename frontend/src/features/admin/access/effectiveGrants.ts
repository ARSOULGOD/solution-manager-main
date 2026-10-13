/**
 * Read helpers for the access-check screen (task 093, `D10` 🔒). The
 * server is the authority on effective permissions — every request hits
 * deny-by-default server-side (design §15) — so this module never decides
 * what is granted; it only shapes what the server returned for display:
 * collapse repeated grants of one permission to the single assignment an
 * operator would edit — while KEEPING every source that conferred it, so
 * "why do I have this?" is answerable with "directly, and also through
 * the Payments Operations group" — and name a concrete "denied here"
 * example from the catalog. No verb is encoded here, only shape (the
 * catalog is data, §16 Q4).
 */
import { PERMISSION_CATALOG } from '@shared/authz/catalog';

import type { EffectiveGrant, GrantVia } from '../adminRepository';

/** One assignment that confers a permission, as the screen names it. */
export interface EffectiveGrantSource {
  roleId: string;
  grantType: 'direct' | 'inherited';
  grantingScope: readonly string[];
  via: GrantVia;
}

export interface EffectiveGrantRow extends EffectiveGrantSource {
  permissionId: string;
  /**
   * EVERY assignment conferring this permission, deduplicated (§8's
   * "deduplicated union"). The row's own `roleId`/`grantType`/`via` are
   * the most specific of them — the one an operator would edit — and this
   * list is what answers "where else does this come from?", which is the
   * question that matters before removing an assignment and expecting
   * access to change.
   */
  sources: readonly EffectiveGrantSource[];
}

/** Every catalog id, in catalog order — the pool a denial example is drawn from. */
const ALL_PERMISSION_IDS: readonly string[] = PERMISSION_CATALOG.map((entry) => entry.id);

/**
 * One row per permission, sorted by id. When more than one assignment
 * confers the same permission the most specific grant wins — the longest
 * granting scope, and a `direct` grant breaks a tie. That is the
 * assignment an operator would change to change access at this scope.
 */
export function groupEffectiveGrants(grants: readonly EffectiveGrant[]): EffectiveGrantRow[] {
  const byPermission = new Map<string, EffectiveGrantRow>();

  for (const grant of grants) {
    const source: EffectiveGrantSource = {
      roleId: grant.roleId,
      grantType: grant.grantType,
      grantingScope: grant.grantingScope,
      via: grant.via,
    };
    const current = byPermission.get(grant.permissionId);
    if (current === undefined) {
      byPermission.set(grant.permissionId, {
        permissionId: grant.permissionId,
        ...source,
        sources: [source],
      });
      continue;
    }
    const sources = containsSource(current.sources, source)
      ? current.sources
      : [...current.sources, source];
    byPermission.set(
      grant.permissionId,
      isMoreSpecific(source, current)
        ? { permissionId: grant.permissionId, ...source, sources }
        : { ...current, sources },
    );
  }

  return [...byPermission.values()].sort((a, b) => a.permissionId.localeCompare(b.permissionId));
}

function sourceKey(source: EffectiveGrantSource): string {
  return [
    source.roleId,
    source.grantingScope.join('/'),
    source.via.kind,
    source.via.kind === 'group' ? source.via.groupId : '',
  ].join('|');
}

function containsSource(
  sources: readonly EffectiveGrantSource[],
  candidate: EffectiveGrantSource,
): boolean {
  const key = sourceKey(candidate);
  return sources.some((source) => sourceKey(source) === key);
}

function isMoreSpecific(candidate: EffectiveGrantSource, current: EffectiveGrantSource): boolean {
  if (candidate.grantingScope.length !== current.grantingScope.length) {
    return candidate.grantingScope.length > current.grantingScope.length;
  }
  // A same-scope tie goes to a DIRECT grant over an inherited one, and —
  // at the same grant type — to the principal's own assignment over one
  // arriving through a group. That is the assignment an operator would
  // change to change access here.
  if (candidate.grantType !== current.grantType) {
    return candidate.grantType === 'direct';
  }
  return candidate.via.kind === 'direct' && current.via.kind !== 'direct';
}

/**
 * The first catalog permission this principal does NOT hold at this scope
 * — a concrete "this is denied" example. `undefined` when every catalog
 * permission is granted (a `'*'` role at or above the scope).
 */
export function firstDeniedPermissionId(
  grantedPermissionIds: Iterable<string>,
): string | undefined {
  const granted = new Set(grantedPermissionIds);
  return ALL_PERMISSION_IDS.find((id) => !granted.has(id));
}
