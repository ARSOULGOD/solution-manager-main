/**
 * RBAC fixtures (task 033, UI §A.13.5, §A.12.2–§A.12.5). Ported from
 * `docs/design/frontend/reference/app/admin_data.jsx`. The permission
 * CATALOG itself already lives in `shared/authz/catalog.ts` (task 032) —
 * this module supplies roles, principals, the scope tree, assignments and
 * local users, none of which have a real endpoint (`AdminRepository` is
 * `CONTRACT: TBD`, §9.2, Q4/Q5).
 *
 * **`SCOPE_TREE` is DERIVED, not written** (task 133, `FE-D46`, `FE-D53`). Its
 * organization/sub-org/environment levels come from `ORGANIZATIONS` and its
 * instance leaves from `CACHE_INSTANCES`, so the tree an admin grants
 * against cannot drift from the hierarchy the declare cascade offers — it
 * had already drifted once, this tree knowing two organizations while the
 * instance rows carried six. `D20` later mapped those six former
 * Organizations to Sub-organizations beneath `Acme Financial`.
 */
import { ORGANIZATIONS, environmentsOf } from './hierarchy';
import { CACHE_INSTANCES } from './instances';

export interface RbacRole {
  id: string;
  name: string;
  kind: 'built-in' | 'custom';
  description: string;
  /** `'*'` = every permission, at every scope (Solace Super-admin only). */
  permissionIds: readonly string[] | '*';
  clonedFrom?: string;
}

export const RBAC_ROLES: readonly RbacRole[] = [
  {
    id: 'super',
    name: 'Solace Super-admin',
    kind: 'built-in',
    description: 'Every permission, across all organizations.',
    permissionIds: '*',
  },
  {
    id: 'org-op',
    name: 'Org Operator',
    kind: 'built-in',
    description: 'Operate instances within one organization.',
    permissionIds: [
      'instance.view',
      'config.view',
      'config.edit',
      'config.apply',
      'instance.restart',
      'health.view',
      'logs.read',
    ],
  },
  {
    id: 'placement',
    name: 'Placement Manager',
    kind: 'built-in',
    description: 'Create instances and place them on targets.',
    permissionIds: [
      'instance.view',
      'instance.create',
      'instance.bindTarget',
      'target.view',
      'target.use',
    ],
  },
  {
    id: 'auditor',
    name: 'Read-only Auditor',
    kind: 'built-in',
    description: 'See everything, change nothing.',
    permissionIds: [
      'solution.view',
      'instance.view',
      'config.view',
      'health.view',
      'logs.read',
      'metrics.query',
      'dashboard.view',
      'role.view',
    ],
  },
  {
    id: 'payments-op',
    name: 'Payments Operator (no restart)',
    kind: 'custom',
    clonedFrom: 'Org Operator',
    description: 'The built-in Org Operator, restart removed — the common real request.',
    permissionIds: [
      'instance.view',
      'config.view',
      'config.edit',
      'config.apply',
      'health.view',
      'logs.read',
    ],
  },
] as const;

export interface RbacPrincipal {
  id: string;
  kind: 'user' | 'group';
  name: string;
  /** email for a user, claim/SCIM source description for a group. */
  sub: string;
  source: 'local' | 'idp';
  /**
   * A deactivated principal keeps its historical assignments — they stay
   * on screen and in the audit trail — but confers nothing. The Access
   * check and the effective-permission computation both drop its grants,
   * and the New assignment dialog refuses to create another.
   */
  status: 'active' | 'deactivated';
  /**
   * GROUPS only: the organization that owns the group (`D20`, `FE-D53`). A group
   * may never be granted outside it, which is the one rule that makes a
   * group safe to hand to an organization's own administrator. Absent for
   * a user — a user is not owned by the hierarchy, only granted within it.
   */
  organization?: string;
}

export const RBAC_PRINCIPALS: readonly RbacPrincipal[] = [
  {
    id: 'u-devon',
    kind: 'user',
    name: 'Devon Ramirez',
    sub: 'devon.ramirez@acme.example',
    source: 'local',
    status: 'active',
  },
  {
    id: 'u-mira',
    kind: 'user',
    name: 'Mira Chen',
    sub: 'mira.chen@acme.example',
    source: 'local',
    status: 'active',
  },
  {
    id: 'u-sam',
    kind: 'user',
    name: 'Sam Okoye',
    sub: 'sam.okoye@acme.example',
    source: 'local',
    status: 'active',
  },
  {
    id: 'g-pay-ops',
    kind: 'group',
    name: 'payments-operators',
    sub: 'IdP group · OIDC claim',
    source: 'idp',
    status: 'active',
    organization: 'Acme Financial',
  },
  {
    id: 'g-sre',
    kind: 'group',
    name: 'platform-sre',
    sub: 'IdP group · SCIM',
    source: 'idp',
    status: 'active',
    organization: 'Acme Financial',
  },
] as const;

/**
 * A node in the grantable scope hierarchy. `'All'` is the synthetic root
 * (`D20`, `FE-D46`) — it is not an organization, it is the top of the
 * forest, and its path is the EMPTY array. Organization is the first real
 * node and the server-derived isolation boundary.
 */
export interface ScopeNode {
  name: string;
  level: 'All' | 'Organization' | 'Sub-organization' | 'Environment' | 'Instance';
  children?: readonly ScopeNode[];
}

/**
 * The label of the synthetic root. Held here rather than in `messages.en.ts`
 * because it is fixture DATA the server would return in a real deployment —
 * `scopePathString` supplies the localized copy for the empty path, which is
 * the string an operator actually reads.
 */
export const ALL_SCOPE_LABEL = 'All organizations';

function instanceLeavesFor(org: string, subOrg: string, env: string): readonly ScopeNode[] {
  return CACHE_INSTANCES.filter(
    (row) => row.org === org && row.subOrg === subOrg && row.env === env,
  ).map((row) => ({ name: row.name, level: 'Instance' as const }));
}

/**
 * DERIVED from `ORGANIZATIONS` + `CACHE_INSTANCES` (task 133). Rooted at the
 * synthetic `All organizations` node, whose scope path is `[]`.
 */
export const SCOPE_TREE: ScopeNode = {
  name: ALL_SCOPE_LABEL,
  level: 'All',
  children: ORGANIZATIONS.map((org) => ({
    name: org.name,
    level: 'Organization' as const,
    children: org.subOrgs.map((subOrg) => ({
      name: subOrg.name,
      level: 'Sub-organization' as const,
      children: environmentsOf(org.name, subOrg.name).map((env) => {
        const instances = instanceLeavesFor(org.name, subOrg.name, env);
        return {
          name: env,
          level: 'Environment' as const,
          ...(instances.length > 0 ? { children: instances } : {}),
        };
      }),
    })),
  })),
};

/**
 * The level of the hierarchy an assignment is made at, derived from the
 * length of its scope path (`D20`, `FE-D53`). It is DERIVED rather than stored so
 * a path and a declared level can never disagree — `scopeLevelOf` in
 * `features/admin/assignments/scopePath.ts` is the one implementation.
 */
export type ScopeLevel = 'all' | 'organization' | 'subOrganization' | 'environment' | 'instance';

export interface RbacAssignment {
  id: string;
  principalId: string;
  roleId: string;
  /**
   * Path from the Organization root, e.g. `["Acme Financial", "Payments"]`.
   * The EMPTY array is the installer-managed global scope — "all
   * organizations" (`D19`, `D20`). `isScopePrefix([], x)` is true for
   * every `x`.
   */
  scope: readonly string[];
  /**
   * **Revoked, never deleted.** An assignment that once conferred access
   * is the record explaining why something happened, so removing one flips
   * this rather than dropping the row — the same "deactivate, never
   * delete" rule the Users screen already applies to accounts. Only
   * `active` rows confer anything.
   */
  status: 'active' | 'revoked';
  assignedBy: string;
  /** ISO-8601 UTC (`B/D15`). */
  assignedAt: string;
}

const SEEDED_BY = 'Devon Ramirez';

export const RBAC_ASSIGNMENTS: readonly RbacAssignment[] = [
  {
    id: 'a1',
    principalId: 'u-devon',
    roleId: 'super',
    scope: [],
    status: 'active',
    assignedBy: 'installer bootstrap',
    assignedAt: '2026-01-08T10:00:00Z',
  },
  {
    id: 'a2',
    principalId: 'u-mira',
    roleId: 'org-op',
    scope: ['Acme Financial'],
    status: 'active',
    assignedBy: SEEDED_BY,
    assignedAt: '2026-02-14T09:30:00Z',
  },
  {
    id: 'a3',
    principalId: 'g-pay-ops',
    roleId: 'placement',
    scope: ['Acme Financial', 'Payments', 'PROD'],
    status: 'active',
    assignedBy: SEEDED_BY,
    assignedAt: '2026-05-21T08:00:00Z',
  },
  {
    id: 'a4',
    principalId: 'u-sam',
    roleId: 'auditor',
    scope: ['Acme Financial', 'Trading'],
    status: 'active',
    assignedBy: SEEDED_BY,
    assignedAt: '2026-03-02T15:45:00Z',
  },
  {
    id: 'a5',
    principalId: 'g-sre',
    roleId: 'org-op',
    scope: ['Acme Financial', 'Trading', 'PROD'],
    status: 'active',
    assignedBy: SEEDED_BY,
    assignedAt: '2026-05-21T08:05:00Z',
  },
  // Local groups (Groups management). `grp-payments-ops` is granted at a
  // SUB-ORGANIZATION and `grp-trading-readonly` at an ORGANIZATION, so the
  // Access check has a real inheritance chain to explain rather than one
  // level repeated.
  {
    id: 'a6',
    principalId: 'grp-payments-ops',
    roleId: 'org-op',
    scope: ['Acme Financial', 'Payments'],
    status: 'active',
    assignedBy: SEEDED_BY,
    assignedAt: '2026-06-02T09:20:00Z',
  },
  {
    id: 'a7',
    principalId: 'grp-trading-readonly',
    roleId: 'auditor',
    scope: ['Acme Financial', 'Trading'],
    status: 'active',
    assignedBy: SEEDED_BY,
    assignedAt: '2026-06-11T11:05:00Z',
  },
] as const;

export interface LocalUser {
  id: string;
  name: string;
  email: string;
  status: 'active' | 'deactivated';
  lastSignIn: string;
  createdBy: string;
  /**
   * The organization this account belongs to, held as its NAME for the
   * same reason `UserGroup.organization` is (see `data/groups.ts`). Group
   * membership is constrained by it: a group owned by one organization may
   * only contain users of that organization, so an administrator cannot
   * grant across the boundary by the back door of a membership row.
   */
  organization: string;
  /**
   * The **Bootstrap Super Admin** (`D19` 🔒, `FE-D50`) — created
   * directly in the database at install, holding the built-in super-admin
   * role at the root scope. The console **never creates one**, and the last
   * active one cannot be deactivated through the UI: an install with no
   * active root admin cannot be recovered from this console.
   *
   * ⚠️ A **flag**, never a credential. No password, hash, token or reset
   * key appears in this fixture, in any API response, or in frontend state
   * (`D19`, `I6`). Hashing and forced replacement at first sign-in are
   * install-time obligations.
   */
  isBootstrapAdmin?: boolean;
}

export const LOCAL_USERS: readonly LocalUser[] = [
  {
    id: 'u-devon',
    name: 'Devon Ramirez',
    email: 'devon.ramirez@acme.example',
    status: 'active',
    lastSignIn: '2m ago',
    createdBy: 'installer bootstrap',
    organization: 'Acme Financial',
    isBootstrapAdmin: true,
  },
  {
    id: 'u-mira',
    name: 'Mira Chen',
    email: 'mira.chen@acme.example',
    status: 'active',
    lastSignIn: '1h ago',
    createdBy: 'Devon Ramirez',
    organization: 'Acme Financial',
  },
  {
    id: 'u-sam',
    name: 'Sam Okoye',
    email: 'sam.okoye@acme.example',
    status: 'active',
    lastSignIn: 'yesterday',
    createdBy: 'Devon Ramirez',
    organization: 'Acme Financial',
  },
  {
    id: 'u-priya',
    name: 'Priya Nair',
    email: 'priya.nair@acme.example',
    status: 'deactivated',
    lastSignIn: '3mo ago',
    createdBy: 'Devon Ramirez',
    organization: 'Acme Financial',
  },
] as const;
