/**
 * The Groups permission mapping (`D18` 🔒, `FE-D47`).
 *
 * These tests exist because the mapping is the ONE place a Groups action
 * is joined to a verb — no call site names one — so a regression here is
 * invisible everywhere else until an operator sees a control they should
 * not have.
 */
import { describe, expect, it } from 'vitest';

import { PERMISSION_CATALOG } from '@shared/authz/catalog';
import type { GrantableVerb } from '@shared/authz/permissions';

import { GROUP_PERMISSIONS } from './groupPermissions';

const CATALOG_IDS = new Set<string>(PERMISSION_CATALOG.map((entry) => entry.id));

/**
 * Listed rather than taken from `Object.values`, whose element type erases
 * to `any` — the same reason `groupPermissions.ts` lists its
 * `requireGrantable` calls instead of iterating them.
 */
const MAPPED_VERBS: readonly GrantableVerb[] = [
  GROUP_PERMISSIONS.view,
  GROUP_PERMISSIONS.create,
  GROUP_PERMISSIONS.edit,
  GROUP_PERMISSIONS.deactivate,
  GROUP_PERMISSIONS.memberManage,
  GROUP_PERMISSIONS.roleAssign,
];

describe('GROUP_PERMISSIONS', () => {
  it('maps every Groups action onto its own ratified verb', () => {
    expect(GROUP_PERMISSIONS).toEqual({
      view: 'group.view',
      create: 'group.create',
      edit: 'group.edit',
      deactivate: 'group.deactivate',
      memberManage: 'group.member.manage',
      roleAssign: 'role.assign',
    });
  });

  it('gates membership on group.member.manage — never on a user.* verb', () => {
    // Weakening this to `user.create` / `user.deactivate` would let a
    // user-administrator escalate privilege through a membership row,
    // because a member inherits every role the group holds (`D18`).
    expect(GROUP_PERMISSIONS.memberManage).toBe('group.member.manage');
    expect(GROUP_PERMISSIONS.memberManage.startsWith('user.')).toBe(false);
  });

  it('gates a group role assignment on role.assign, not a group-specific spelling', () => {
    expect(GROUP_PERMISSIONS.roleAssign).toBe('role.assign');
    expect(CATALOG_IDS.has('group.role.assign')).toBe(false);
  });

  it('has no separate member-view key — group.view covers member visibility', () => {
    expect('memberView' in GROUP_PERMISSIONS).toBe(false);
    expect(CATALOG_IDS.has('group.member.view')).toBe(false);
  });

  it('borrows no user.* verb for any action', () => {
    for (const verb of MAPPED_VERBS) {
      expect(verb.startsWith('user.')).toBe(false);
    }
  });

  it('names only verbs the ratified catalog carries', () => {
    for (const verb of MAPPED_VERBS) {
      expect(CATALOG_IDS.has(verb)).toBe(true);
    }
  });
});
