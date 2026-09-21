/**
 * Local group fixtures (Groups management, `D10` 🔒, `SM-D23`, `FE-D46`).
 *
 * A **group** is a named collection of local users that can hold role
 * assignments, so an administrator grants once to the collection rather
 * than N times to N people. It is the second principal kind the RBAC model
 * already carries (`RbacPrincipal.kind === 'group'`) — this module is what
 * makes a *locally managed* one exist, rather than only the IdP-sourced
 * groups the earlier fixture asserted.
 *
 * **A group is owned by exactly one ORGANIZATION** — the top of the
 * hierarchy (`FE-D46`: Organization → Sub-organization → Environment,
 * with Organization as the top level). The owning organization is held as its NAME,
 * because that is what identifies a hierarchy node everywhere else in this
 * console: `ORGANIZATIONS[].name`, `RbacAssignment.scope` path segments and
 * `CACHE_INSTANCES[].org` are all names. Inventing an opaque
 * `organizationId` here would have created a second, unjoinable identity
 * for the same node.
 *
 * **`source` is IdP-ready, not IdP-implemented** (§12 of the Groups brief).
 * `'local'`/`'idp'` reuses `RbacPrincipal.source`'s own union rather than
 * introducing a parallel `'identityProvider'` spelling; `externalId` is
 * carried so a synchronized group needs no schema change. Nothing here
 * synchronizes anything — an `idp` group is simply rendered read-only.
 *
 * INVENTED, like every other fixture in this folder — `AdminRepository` is
 * `CONTRACT: TBD` (§9.2, §16 Q4/Q5). Organizations are drawn from
 * `ORGANIZATIONS` so a group can never be owned by an organization the
 * hierarchy cascade does not offer.
 */

export type GroupStatus = 'active' | 'deactivated';

/** Same union as `RbacPrincipal.source` — deliberately not a second spelling. */
export type GroupSource = 'local' | 'idp';

export interface UserGroup {
  id: string;
  name: string;
  description?: string;
  /** The owning organization's NAME — the first segment of any scope it may be granted at. */
  organization: string;
  status: GroupStatus;
  /** `'idp'` groups are read-only here: membership is owned by the provider (§12). */
  source: GroupSource;
  /** The provider's own id for an `'idp'` group. Absent for a local one. */
  externalId?: string;
  memberCount: number;
  assignmentCount: number;
  createdBy: string;
  /** ISO-8601 UTC (`B/D15`) — formatted for display by `@shared/format`. */
  createdAt: string;
  updatedBy?: string;
  updatedAt: string;
}

export interface GroupMember {
  groupId: string;
  userId: string;
  addedBy: string;
  /** ISO-8601 UTC (`B/D15`). */
  addedAt: string;
}

/**
 * `memberCount` / `assignmentCount` are DERIVED by the fixture store from
 * the membership and assignment tables — the numbers written here are the
 * seed's own consistent values, and `rbacStore` recomputes them on every
 * read so they cannot drift the way `instanceCount` once did (FE-120).
 */
export const LOCAL_GROUPS: readonly UserGroup[] = [
  {
    id: 'grp-payments-ops',
    name: 'Payments Operations',
    description: 'Day-to-day operators for the Payments ledger.',
    organization: 'Acme Financial',
    status: 'active',
    source: 'local',
    memberCount: 2,
    assignmentCount: 1,
    createdBy: 'Devon Ramirez',
    createdAt: '2026-06-02T09:14:00Z',
    updatedBy: 'Devon Ramirez',
    updatedAt: '2026-08-19T16:40:00Z',
  },
  {
    id: 'grp-trading-readonly',
    name: 'Trading Auditors',
    description: 'Read-only access across the Trading organization.',
    organization: 'Acme Financial',
    status: 'active',
    source: 'local',
    memberCount: 1,
    assignmentCount: 1,
    createdBy: 'Devon Ramirez',
    createdAt: '2026-06-11T11:02:00Z',
    updatedAt: '2026-06-11T11:02:00Z',
  },
  {
    id: 'grp-risk-retired',
    name: 'Risk Batch (retired)',
    description: 'Superseded by the Risk platform rota. Kept for audit attribution.',
    organization: 'Acme Financial',
    status: 'deactivated',
    source: 'local',
    memberCount: 1,
    assignmentCount: 0,
    createdBy: 'Devon Ramirez',
    createdAt: '2026-03-04T08:00:00Z',
    updatedBy: 'Mira Chen',
    updatedAt: '2026-07-28T13:20:00Z',
  },
  {
    id: 'g-pay-ops',
    name: 'payments-operators',
    description: 'Synchronized from the identity provider (OIDC claim).',
    organization: 'Acme Financial',
    status: 'active',
    source: 'idp',
    externalId: 'oidc:payments-operators',
    memberCount: 0,
    assignmentCount: 1,
    createdBy: 'identity provider',
    createdAt: '2026-05-20T07:30:00Z',
    updatedAt: '2026-08-30T04:00:00Z',
  },
  {
    id: 'g-sre',
    name: 'platform-sre',
    description: 'Synchronized from the identity provider (SCIM).',
    organization: 'Acme Financial',
    status: 'active',
    source: 'idp',
    externalId: 'scim:platform-sre',
    memberCount: 0,
    assignmentCount: 1,
    createdBy: 'identity provider',
    createdAt: '2026-05-20T07:30:00Z',
    updatedAt: '2026-08-30T04:00:00Z',
  },
] as const;

export const GROUP_MEMBERSHIPS: readonly GroupMember[] = [
  {
    groupId: 'grp-payments-ops',
    userId: 'u-mira',
    addedBy: 'Devon Ramirez',
    addedAt: '2026-06-02T09:15:00Z',
  },
  {
    groupId: 'grp-payments-ops',
    userId: 'u-devon',
    addedBy: 'Devon Ramirez',
    addedAt: '2026-08-19T16:40:00Z',
  },
  {
    groupId: 'grp-trading-readonly',
    userId: 'u-sam',
    addedBy: 'Devon Ramirez',
    addedAt: '2026-06-11T11:03:00Z',
  },
  // Priya is deactivated as a USER but remains a member — the screen must
  // say so rather than quietly dropping her, because her past actions stay
  // attributable and a reactivation would restore this access.
  {
    groupId: 'grp-risk-retired',
    userId: 'u-priya',
    addedBy: 'Devon Ramirez',
    addedAt: '2026-03-04T08:05:00Z',
  },
] as const;
