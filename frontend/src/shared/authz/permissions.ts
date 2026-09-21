/**
 * The permission model (task 032, §6.0.6, `D10` 🔒, `FE-D9`, `FE-D22`).
 *
 * ⚠️ **Frontend RBAC is a COSMETIC overlay, never a security boundary.**
 * Every request still hits deny-by-default on the server (design §15).
 * Hiding or disabling a control here changes what an operator sees; it
 * changes nothing about what the server allows.
 */

export type CatalogStatus = 'grantable' | 'proposed' | 'absent';

export interface PermissionDef {
  id: `${string}.${string}`;
  status: CatalogStatus;
  /** Confirm even when authorized — restart-all, rolling-no-validation, force-raw, etc. (design §15). */
  highConsequence?: boolean;
  /** Needs a grant on BOTH scopes (e.g. `instance.bindTarget` needs both the instance's and the target's scope). */
  twoSided?: boolean;
}

export interface PermissionApi {
  /** Does the current identity hold this permission? */
  has(id: string): boolean;
  /** The catalog's status for this id — `absent` for an id not in the catalog at all. */
  status(id: string): CatalogStatus;
  /**
   * G13. Two modes, never confused elsewhere in this module's callers:
   * `omit` — the control does not exist. `render` — it exists (the
   * DISABLE-with-reason decision, if any, is a separate, non-RBAC concern
   * `Can.tsx` layers on top — see that file).
   */
  gate(id: string): 'omit' | 'render';
}

/**
 * The closed **ratified** operation catalog — `D18` 🔒 (2026-09-04), which
 * amends `D10`'s catalog leg with the five `group.*` verbs and locks the
 * 30 that `FE-D45` had shipped ahead of ratification. A bare runtime
 * string remains a type error at a gate call site, preventing typos from
 * silently becoming a new authorization operation. Adding an operation is
 * a deliberate catalog and server-policy change, not a feature-local
 * string literal.
 *
 * ⚠️ Two ids are **deliberately absent** and must not be added back
 * (`D18`, `FE-D47`): `group.member.view` — `group.view` covers group and
 * member visibility — and `group.role.assign` — `role.assign` already
 * means "assign or revoke a role to or from a principal", and a group is
 * a principal.
 */
export type GrantableVerb =
  | 'solution.view'
  | 'package.load'
  | 'instance.view'
  | 'instance.create'
  | 'instance.bindTarget'
  | 'config.view'
  | 'config.edit'
  | 'config.apply'
  | 'instance.restart'
  | 'config.rollback'
  | 'config.overrideWarning'
  | 'health.view'
  | 'logs.read'
  | 'logs.setLevel'
  | 'metrics.query'
  | 'metrics.export'
  | 'metrics.forceRaw'
  | 'metric.declare'
  | 'dashboard.view'
  | 'dashboard.edit'
  | 'dashboard.reset'
  | 'target.view'
  | 'target.create'
  | 'target.use'
  | 'role.view'
  | 'role.edit'
  | 'role.assign'
  | 'user.view'
  | 'user.create'
  | 'user.deactivate'
  // ---- Groups (`D18` 🔒) ----
  | 'group.view'
  | 'group.create'
  | 'group.edit'
  | 'group.deactivate'
  | 'group.member.manage';

/**
 * The union, enumerated — and the reason it cannot silently drift from
 * `PERMISSION_CATALOG`.
 *
 * The union above and the catalog in `catalog.ts` are two hand-maintained
 * lists of the same ids, and nothing used to compare them: a verb added to
 * one and forgotten in the other was silent. This array closes that in
 * both directions at once. Its **type** rejects an id that is not in the
 * union (a compile error), and `catalog.test.ts` asserts it equals the
 * catalog's ids (a test failure) — so "no bare permission string bypasses
 * `GrantableVerb`" is enforced rather than merely intended.
 *
 * It lives here rather than in `permissions.typetest.ts` because that file
 * executes a deliberate `@ts-expect-error` line against an undeclared
 * binding and therefore cannot be imported at runtime.
 */
export const ALL_GRANTABLE_VERBS: readonly GrantableVerb[] = [
  'solution.view',
  'package.load',
  'instance.view',
  'instance.create',
  'instance.bindTarget',
  'config.view',
  'config.edit',
  'config.apply',
  'instance.restart',
  'config.rollback',
  'config.overrideWarning',
  'health.view',
  'logs.read',
  'logs.setLevel',
  'metrics.query',
  'metrics.export',
  'metrics.forceRaw',
  'metric.declare',
  'dashboard.view',
  'dashboard.edit',
  'dashboard.reset',
  'target.view',
  'target.create',
  'target.use',
  'role.view',
  'role.edit',
  'role.assign',
  'user.view',
  'user.create',
  'user.deactivate',
  'group.view',
  'group.create',
  'group.edit',
  'group.deactivate',
  'group.member.manage',
] as const;

// eslint-disable-next-line @typescript-eslint/no-unused-vars -- the parameter's TYPE is the enforcement; the value is never read.
export function requireGrantable(id: GrantableVerb): void {
  // Intentionally empty. This function exists purely so its parameter
  // type constrains every call site at compile time (§6.0.6).
}

export function createPermissionApi(
  grantedIds: readonly string[],
  catalog: readonly PermissionDef[],
): PermissionApi {
  const catalogById = new Map<string, PermissionDef>(catalog.map((entry) => [entry.id, entry]));
  const granted = new Set(grantedIds);

  return {
    has: (id) => granted.has(id),
    status: (id) => catalogById.get(id)?.status ?? 'absent',
    gate: (id) => (granted.has(id) ? 'render' : 'omit'),
  };
}
