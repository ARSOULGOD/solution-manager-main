/**
 * Shared fixtures for the Groups suites — one definition of "the three
 * groups, four users and one role this feature is tested against", so the
 * list, details and form tests cannot disagree about the world they are
 * asserting on. Not a `*.test.ts` file (Vitest's `include` matches that
 * pattern exactly), so it is a helper, never a suite.
 */
import type { GroupMember, UserGroup } from '@shared/testdoubles/data/groups';
import type { LocalUser, RbacAssignment, RbacRole, ScopeNode } from '@shared/testdoubles/data/rbac';

export const SCOPE_TREE: ScopeNode = {
  name: 'All organizations',
  level: 'All',
  children: [
    {
      name: 'Acme Financial',
      level: 'Organization',
      children: [
        {
          name: 'Payments',
          level: 'Sub-organization',
          children: [{ name: 'PROD', level: 'Environment' }],
        },
        {
          name: 'Trading',
          level: 'Sub-organization',
          children: [{ name: 'TEST', level: 'Environment' }],
        },
      ],
    },
  ],
};

export const ROLES: RbacRole[] = [
  {
    id: 'org-op',
    name: 'Org Operator',
    kind: 'built-in',
    description: 'Operate instances within one organization.',
    permissionIds: ['instance.view', 'config.apply'],
  },
  {
    id: 'auditor',
    name: 'Read-only Auditor',
    kind: 'built-in',
    description: 'See everything, change nothing.',
    permissionIds: ['instance.view'],
  },
];

export const USERS: LocalUser[] = [
  {
    id: 'u-mira',
    name: 'Mira Chen',
    email: 'mira@acme.example',
    status: 'active',
    lastSignIn: '1h ago',
    createdBy: 'Devon Ramirez',
    organization: 'Acme Financial',
  },
  {
    id: 'u-devon',
    name: 'Devon Ramirez',
    email: 'devon@acme.example',
    status: 'active',
    lastSignIn: '2m ago',
    createdBy: 'installer bootstrap',
    organization: 'Acme Financial',
  },
  {
    id: 'u-sam',
    name: 'Sam Okoye',
    email: 'sam@acme.example',
    status: 'active',
    lastSignIn: 'yesterday',
    createdBy: 'Devon Ramirez',
    organization: 'Acme Financial',
  },
  {
    id: 'u-priya',
    name: 'Priya Nair',
    email: 'priya@acme.example',
    status: 'deactivated',
    lastSignIn: '3mo ago',
    createdBy: 'Devon Ramirez',
    organization: 'Acme Financial',
  },
];

export const PAYMENTS_OPS: UserGroup = {
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
};

export const TRADING_AUDITORS: UserGroup = {
  id: 'grp-trading-readonly',
  name: 'Trading Auditors',
  description: 'Read-only access across the Trading organization.',
  organization: 'Acme Financial',
  status: 'deactivated',
  source: 'local',
  memberCount: 1,
  assignmentCount: 0,
  createdBy: 'Devon Ramirez',
  createdAt: '2026-06-11T11:02:00Z',
  updatedAt: '2026-06-11T11:02:00Z',
};

export const IDP_GROUP: UserGroup = {
  id: 'g-sre',
  name: 'platform-sre',
  organization: 'Acme Financial',
  status: 'active',
  source: 'idp',
  externalId: 'scim:platform-sre',
  memberCount: 0,
  assignmentCount: 1,
  createdBy: 'identity provider',
  createdAt: '2026-05-20T07:30:00Z',
  updatedAt: '2026-08-30T04:00:00Z',
};

export const GROUPS: UserGroup[] = [PAYMENTS_OPS, TRADING_AUDITORS, IDP_GROUP];

export const MEMBERS: GroupMember[] = [
  {
    groupId: PAYMENTS_OPS.id,
    userId: 'u-mira',
    addedBy: 'Devon Ramirez',
    addedAt: '2026-06-02T09:15:00Z',
  },
  {
    groupId: PAYMENTS_OPS.id,
    userId: 'u-priya',
    addedBy: 'Devon Ramirez',
    addedAt: '2026-06-02T09:16:00Z',
  },
];

export const GROUP_ASSIGNMENTS: RbacAssignment[] = [
  {
    id: 'a6',
    principalId: PAYMENTS_OPS.id,
    roleId: 'org-op',
    scope: ['Acme Financial', 'Payments'],
    status: 'active',
    assignedBy: 'Devon Ramirez',
    assignedAt: '2026-06-02T09:20:00Z',
  },
];

/**
 * Every verb the Groups screens gate on — the "fully authorized" identity.
 * The ratified set (`D18` 🔒, `FE-D47`); no `user.*` verb appears here any
 * more, which is what makes a test seeded with this identity prove the
 * Groups gates rather than the Users ones.
 */
export const ALL_GROUP_PERMISSIONS = [
  'group.view',
  'group.create',
  'group.edit',
  'group.deactivate',
  'group.member.manage',
  'role.assign',
] as const;
