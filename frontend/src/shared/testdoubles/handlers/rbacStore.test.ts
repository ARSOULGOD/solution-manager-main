/**
 * The effective-permission rules the Access check renders (Groups
 * management §8). These live against the fixture store rather than a
 * component because they are the ANSWER's shape — additive allow, union
 * of direct + group + inherited, nothing from a deactivated principal —
 * and asserting them through a rendered table would test the table.
 */
import { beforeEach, describe, expect, it } from 'vitest';

import {
  addMember,
  assignmentExists,
  createAssignment,
  createGroup,
  effectiveGrantsFor,
  findGroup,
  groupNameTaken,
  listGroups,
  listMembers,
  listAssignments,
  listPrincipals,
  listUsers,
  removeMember,
  resetRbacStore,
  revokeAssignment,
  setGroupStatus,
  setUserActive,
  updateGroup,
} from './rbacStore';

const PAYMENTS_OPS = 'grp-payments-ops';

function permissionsOf(principalId: string, scope: readonly string[]): string[] {
  return [...new Set(effectiveGrantsFor(principalId, scope).map((g) => g.permissionId))].sort();
}

beforeEach(() => {
  resetRbacStore();
});

describe('effectiveGrantsFor', () => {
  it('gives a user their own direct assignment', () => {
    // Mira holds Org Operator directly at the Acme Financial organization.
    expect(permissionsOf('u-mira', ['Acme Financial'])).toContain('config.apply');
    expect(
      effectiveGrantsFor('u-mira', ['Acme Financial']).every((g) => g.grantType === 'direct'),
    ).toBe(
      true,
    );
  });

  it('gives a user the permissions of a group they belong to, naming the group as the source', () => {
    // Devon is a member of Payments Operations, which holds Org Operator
    // at Acme Financial / Payments. He also holds Super-admin directly.
    const grants = effectiveGrantsFor('u-devon', ['Acme Financial', 'Payments']);
    const viaGroup = grants.filter((g) => g.via.kind === 'group');
    expect(viaGroup.length).toBeGreaterThan(0);
    expect(viaGroup[0]?.via.groupName).toBe('Payments Operations');
    expect(viaGroup.map((g) => g.permissionId)).toContain('config.apply');
  });

  it('inherits a parent-scope assignment down to a child scope', () => {
    // The group's assignment is made at Acme Financial / Payments; at the PROD
    // environment beneath it the grant is INHERITED, not direct.
    const grants = effectiveGrantsFor('u-devon', ['Acme Financial', 'Payments', 'PROD']);
    const viaGroup = grants.filter((g) => g.via.kind === 'group');
    expect(viaGroup.length).toBeGreaterThan(0);
    expect(viaGroup.every((g) => g.grantType === 'inherited')).toBe(true);
    expect(viaGroup.every((g) => g.grantingScope.join('/') === 'Acme Financial/Payments')).toBe(
      true,
    );
  });

  it('does not leak a group grant to a scope the assignment does not reach', () => {
    const grants = effectiveGrantsFor('u-devon', ['Acme Financial', 'Trading']);
    expect(grants.some((g) => g.via.kind === 'group')).toBe(false);
  });

  it('is the deduplicated union of every source', () => {
    const grants = effectiveGrantsFor('u-devon', ['Acme Financial', 'Payments']);
    const keys = grants.map((g) =>
      [g.permissionId, g.roleId, g.grantingScope.join('/'), g.via.kind].join('|'),
    );
    expect(new Set(keys).size).toBe(keys.length);
  });

  it('stops conferring the moment a member is removed', () => {
    const before = effectiveGrantsFor('u-mira', ['Acme Financial', 'Payments']).filter(
      (g) => g.via.kind === 'group',
    );
    expect(before.length).toBeGreaterThan(0);

    removeMember(PAYMENTS_OPS, 'u-mira');

    const after = effectiveGrantsFor('u-mira', ['Acme Financial', 'Payments']).filter(
      (g) => g.via.kind === 'group',
    );
    expect(after).toHaveLength(0);
    // Her own direct assignment at Acme Financial is untouched.
    expect(permissionsOf('u-mira', ['Acme Financial', 'Payments'])).toContain('config.apply');
  });

  it('a deactivated GROUP grants nothing, while its records stay readable', () => {
    setGroupStatus(PAYMENTS_OPS, 'deactivated');

    const grants = effectiveGrantsFor('u-mira', ['Acme Financial', 'Payments']);
    expect(grants.some((g) => g.via.kind === 'group')).toBe(false);
    // The membership row and the assignment survive for audit.
    expect(listMembers(PAYMENTS_OPS).length).toBeGreaterThan(0);
    expect(findGroup(PAYMENTS_OPS)?.assignmentCount).toBe(1);
  });

  it('a deactivated USER holds nothing at all', () => {
    setUserActive('u-mira', false);
    expect(effectiveGrantsFor('u-mira', ['Acme Financial'])).toHaveLength(0);
  });

  it('a revoked assignment confers nothing', () => {
    // Sam holds Read-only Auditor at Trading directly, AND through the
    // Trading Auditors group — so revoking his own must remove the DIRECT
    // source while leaving the group-sourced one, not remove the
    // permission outright. That distinction is the point of the screen.
    expect(
      effectiveGrantsFor('u-sam', ['Acme Financial', 'Trading']).some(
        (g) => g.via.kind === 'direct',
      ),
    ).toBe(true);
    revokeAssignment('a4');
    const after = effectiveGrantsFor('u-sam', ['Acme Financial', 'Trading']);
    expect(after.some((g) => g.via.kind === 'direct')).toBe(false);
    expect(after.some((g) => g.via.kind === 'group')).toBe(true);

    // Revoke the group's assignment too and the access is gone entirely.
    revokeAssignment('a7');
    expect(effectiveGrantsFor('u-sam', ['Acme Financial', 'Trading'])).toHaveLength(0);
  });

  it('has no deny rule — semantics are additive allow only', () => {
    // Adding a second, narrower role can only ever ADD permissions.
    const before = permissionsOf('u-sam', ['Acme Financial', 'Trading']);
    createAssignment({
      principalId: 'u-sam',
      roleId: 'placement',
      scope: ['Acme Financial', 'Trading'],
    });
    const after = permissionsOf('u-sam', ['Acme Financial', 'Trading']);
    expect(before.every((id) => after.includes(id))).toBe(true);
    expect(after.length).toBeGreaterThan(before.length);
  });
});

describe('group writes', () => {
  it('derives memberCount and assignmentCount rather than storing them', () => {
    const group = findGroup(PAYMENTS_OPS);
    expect(group?.memberCount).toBe(2);
    removeMember(PAYMENTS_OPS, 'u-mira');
    expect(findGroup(PAYMENTS_OPS)?.memberCount).toBe(1);
  });

  it('refuses a cross-organization membership', () => {
    const globex = createGroup({ name: 'Globex Operations', organization: 'Globex', memberIds: [] });
    // Sam belongs to Acme Financial, so he cannot join a Globex group.
    expect(addMember(globex.id, 'u-sam')).toBeUndefined();
    expect(listMembers(globex.id).some((m) => m.userId === 'u-sam')).toBe(false);
  });

  it('never creates a duplicate membership row', () => {
    addMember(PAYMENTS_OPS, 'u-mira');
    expect(listMembers(PAYMENTS_OPS).filter((m) => m.userId === 'u-mira')).toHaveLength(1);
  });

  it('creates a group with initial members and makes it an assignable principal', () => {
    const created = createGroup({
      name: 'Payments Reviewers',
      organization: 'Acme Financial',
      memberIds: ['u-mira'],
    });
    expect(created.memberCount).toBe(1);
    expect(listPrincipals().some((p) => p.id === created.id && p.kind === 'group')).toBe(true);
  });

  it('enforces case-insensitive name uniqueness within one organization only', () => {
    expect(groupNameTaken('payments operations', 'Acme Financial')).toBe(true);
    expect(groupNameTaken('payments operations', 'Globex')).toBe(false);
    expect(groupNameTaken('payments operations', 'Acme Financial', PAYMENTS_OPS)).toBe(false);
  });

  it('edits name and description, and clears a description when emptied', () => {
    updateGroup(PAYMENTS_OPS, { name: '  Renamed  ', description: '' });
    const group = findGroup(PAYMENTS_OPS);
    expect(group?.name).toBe('Renamed');
    expect(group?.description).toBeUndefined();
    expect(group?.updatedBy).toBe('Devon Ramirez');
  });

  it('deactivates and reactivates rather than deleting', () => {
    setGroupStatus(PAYMENTS_OPS, 'deactivated');
    expect(findGroup(PAYMENTS_OPS)?.status).toBe('deactivated');
    expect(listGroups().some((g) => g.id === PAYMENTS_OPS)).toBe(true);
    setGroupStatus(PAYMENTS_OPS, 'active');
    expect(findGroup(PAYMENTS_OPS)?.status).toBe('active');
  });
});

describe('assignmentExists', () => {
  it('is true only for the same principal, role and EXACT scope', () => {
    const same = {
      principalId: PAYMENTS_OPS,
      roleId: 'org-op',
      scope: ['Acme Financial', 'Payments'],
    };
    expect(assignmentExists(same)).toBe(true);
    expect(assignmentExists({ ...same, scope: ['Acme Financial'] })).toBe(false);
    expect(assignmentExists({ ...same, roleId: 'auditor' })).toBe(false);
  });

  it('ignores a revoked assignment — the same grant can be made again', () => {
    const draft = {
      principalId: PAYMENTS_OPS,
      roleId: 'org-op',
      scope: ['Acme Financial', 'Payments'],
    };
    revokeAssignment('a6');
    expect(assignmentExists(draft)).toBe(false);
  });
});

describe('the Bootstrap Super Admin (`D19` 🔒)', () => {
  it('refuses to deactivate the last active one', () => {
    expect(setUserActive('u-devon', false)).toBe(false);
    expect(listUsers().find((user) => user.id === 'u-devon')?.status).toBe('active');
  });

  it('deactivates an ordinary user without complaint', () => {
    expect(setUserActive('u-mira', false)).toBe(true);
    expect(listUsers().find((user) => user.id === 'u-mira')?.status).toBe('deactivated');
  });

  it('never blocks REACTIVATION — the rule only guards the count reaching zero', () => {
    expect(setUserActive('u-priya', true)).toBe(true);
    expect(listUsers().find((user) => user.id === 'u-priya')?.status).toBe('active');
  });

  it('is the fixture\u2019s one bootstrap admin, and it holds the root grant', () => {
    const bootstrap = listUsers().filter((user) => user.isBootstrapAdmin === true);
    expect(bootstrap.map((user) => user.id)).toEqual(['u-devon']);
    const root = listAssignments().find(
      (assignment) => assignment.principalId === 'u-devon' && assignment.scope.length === 0,
    );
    expect(root?.roleId).toBe('super');
    expect(root?.assignedBy).toBe('installer bootstrap');
  });

  it('carries no credential of any kind on a user row (`D19`, `I6`)', () => {
    // A password, hash, token or reset key must never reach the client.
    // This asserts the SHAPE, because a leak here would be invisible until
    // it appeared in a log or a devtools panel.
    for (const user of listUsers()) {
      for (const key of Object.keys(user)) {
        expect(/pass|secret|cred|token|hash|otp/i.test(key)).toBe(false);
      }
    }
  });
});
