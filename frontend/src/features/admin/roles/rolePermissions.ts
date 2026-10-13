/**
 * Read helpers over the permission catalog for the role screens (task
 * 091). The catalog itself is `@shared/authz/catalog` — data, unratified
 * (§16 Q4); these helpers never encode a specific verb, only shape:
 * resolve an id to its `PermissionDef`, expand a role's grant set
 * (`'*'` -> every id), and answer "does this set include a
 * high-consequence verb". Adding a verb changes the array they read, not
 * this file.
 */
import { PERMISSION_CATALOG, PERMISSION_GROUPS } from '@shared/authz/catalog';
import type { PermissionDef } from '@shared/authz/permissions';
import type { RbacRole } from '@shared/testdoubles/data/rbac';

export const PERMISSION_BY_ID: ReadonlyMap<string, PermissionDef> = new Map(
  PERMISSION_CATALOG.map((entry) => [entry.id, entry]),
);

/** Every catalog id, in `PERMISSION_GROUPS` order - the concrete grant set behind a `'*'` role. */
export const ALL_PERMISSION_IDS: readonly string[] = PERMISSION_GROUPS.flatMap(
  (group) => group.permissionIds,
);

/** A role's grant set as concrete ids. `'*'` (Solace Super-admin only) expands to the whole catalog. */
export function expandRolePermissions(permissionIds: RbacRole['permissionIds']): readonly string[] {
  return permissionIds === '*' ? ALL_PERMISSION_IDS : permissionIds;
}

export function includesHighConsequence(permissionIds: Iterable<string>): boolean {
  for (const id of permissionIds) {
    if (PERMISSION_BY_ID.get(id)?.highConsequence === true) {
      return true;
    }
  }
  return false;
}
