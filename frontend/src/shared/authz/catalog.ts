/**
 * The permission catalog (task 032, `D10` 🔒 + `D18` 🔒, UI §A.12.1).
 * Ported from the UI reference's `admin_data.jsx` `PERM_GROUPS` — the model
 * (resource-grouped permissions with a catalog status) is what §A.12.1
 * specifies. **The catalog is data** — amending it (adding a
 * verb, flipping a status) never touches this file's shape or any
 * consumer's code, only this array (plan §6.0.6's `Ready: READY` note).
 *
 * All 35 operations listed here are grantable under the **ratified**
 * catalog — `D18` 🔒 (2026-09-04) amends `D10`'s catalog leg, locking the
 * 30 the console had shipped ahead of ratification under `FE-D45` and
 * adding five `group.*` verbs. The server remains the security boundary;
 * the frontend only projects the effective grants it receives.
 */
import type { PermissionDef } from './permissions';

export interface PermissionGroup {
  resource: string;
  label: string;
  permissionIds: readonly string[];
}

export const PERMISSION_CATALOG: readonly PermissionDef[] = [
  { id: 'solution.view', status: 'grantable' },
  { id: 'package.load', status: 'grantable', highConsequence: true },

  { id: 'instance.view', status: 'grantable' },
  { id: 'instance.create', status: 'grantable' },
  { id: 'instance.bindTarget', status: 'grantable', highConsequence: true, twoSided: true },

  { id: 'config.view', status: 'grantable' },
  { id: 'config.edit', status: 'grantable' },
  { id: 'config.apply', status: 'grantable', highConsequence: true },

  { id: 'instance.restart', status: 'grantable', highConsequence: true },
  { id: 'config.rollback', status: 'grantable', highConsequence: true },
  { id: 'config.overrideWarning', status: 'grantable', highConsequence: true },

  { id: 'health.view', status: 'grantable' },
  { id: 'logs.read', status: 'grantable' },
  { id: 'logs.setLevel', status: 'grantable', highConsequence: true },

  { id: 'metrics.query', status: 'grantable' },
  { id: 'metrics.export', status: 'grantable' },
  { id: 'metrics.forceRaw', status: 'grantable', highConsequence: true },
  { id: 'metric.declare', status: 'grantable' },
  { id: 'dashboard.view', status: 'grantable' },
  { id: 'dashboard.edit', status: 'grantable' },
  { id: 'dashboard.reset', status: 'grantable' },

  { id: 'target.view', status: 'grantable' },
  { id: 'target.create', status: 'grantable' },
  { id: 'target.use', status: 'grantable', twoSided: true },

  { id: 'role.view', status: 'grantable' },
  { id: 'role.edit', status: 'grantable', highConsequence: true },
  { id: 'role.assign', status: 'grantable', highConsequence: true },

  { id: 'user.view', status: 'grantable' },
  { id: 'user.create', status: 'grantable' },
  { id: 'user.deactivate', status: 'grantable' },

  // Groups (`D18` 🔒, 2026-09-04). `group.view` covers group AND member
  // visibility, and `role.assign` covers granting a role to a group — so
  // `group.member.view` and `group.role.assign` are deliberately absent
  // (`D18`, `FE-D47`). `group.member.manage` is high-consequence because a
  // membership row confers every role the group holds.
  { id: 'group.view', status: 'grantable' },
  { id: 'group.create', status: 'grantable' },
  { id: 'group.edit', status: 'grantable' },
  { id: 'group.deactivate', status: 'grantable' },
  { id: 'group.member.manage', status: 'grantable', highConsequence: true },
] as const;

/** Resource groupings for the Admin role editor (§A.12.2), same order as the reference. */
export const PERMISSION_GROUPS: readonly PermissionGroup[] = [
  { resource: 'solution', label: 'Solution', permissionIds: ['solution.view', 'package.load'] },
  {
    resource: 'instance',
    label: 'Instance',
    permissionIds: ['instance.view', 'instance.create', 'instance.bindTarget'],
  },
  {
    resource: 'config',
    label: 'Configuration',
    permissionIds: ['config.view', 'config.edit', 'config.apply'],
  },
  {
    resource: 'lifecycle',
    label: 'Lifecycle',
    permissionIds: ['instance.restart', 'config.rollback', 'config.overrideWarning'],
  },
  {
    resource: 'observability',
    label: 'Health & logs',
    permissionIds: ['health.view', 'logs.read', 'logs.setLevel'],
  },
  {
    resource: 'metrics',
    label: 'Metrics',
    permissionIds: ['metrics.query', 'metrics.export', 'metrics.forceRaw', 'metric.declare'],
  },
  {
    resource: 'dashboard',
    label: 'Dashboards',
    permissionIds: ['dashboard.view', 'dashboard.edit', 'dashboard.reset'],
  },
  {
    resource: 'target',
    label: 'Target',
    permissionIds: ['target.view', 'target.create', 'target.use'],
  },
  { resource: 'role', label: 'Roles', permissionIds: ['role.view', 'role.edit', 'role.assign'] },
  {
    resource: 'user',
    label: 'Users',
    permissionIds: ['user.view', 'user.create', 'user.deactivate'],
  },
  {
    resource: 'group',
    label: 'Groups',
    permissionIds: [
      'group.view',
      'group.create',
      'group.edit',
      'group.deactivate',
      'group.member.manage',
    ],
  },
] as const;
