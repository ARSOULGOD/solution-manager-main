import { describe, expect, it } from 'vitest';

import { PERMISSION_CATALOG } from '@shared/authz/catalog';

import type { EffectiveGrant } from '../adminRepository';

import { firstDeniedPermissionId, groupEffectiveGrants } from './effectiveGrants';

const at =
  (grantType: EffectiveGrant['grantType'], grantingScope: string[]) =>
  (permissionId: string, roleId = 'role-x'): EffectiveGrant => ({
    permissionId,
    roleId,
    grantType,
    grantingScope,
    via: { kind: 'direct' },
  });

describe('groupEffectiveGrants', () => {
  it('returns one row per permission, sorted by id', () => {
    const direct = at('direct', ['t', 'org']);
    const rows = groupEffectiveGrants([
      direct('config.view'),
      direct('instance.view'),
      direct('config.edit'),
    ]);
    expect(rows.map((row) => row.permissionId)).toEqual([
      'config.edit',
      'config.view',
      'instance.view',
    ]);
  });

  it('keeps the most specific grant when two assignments confer the same permission', () => {
    const inherited = at('inherited', ['t', 'org']);
    const direct = at('direct', ['t', 'org', 'sub', 'env']);
    const rows = groupEffectiveGrants([
      inherited('config.apply', 'org-op'),
      direct('config.apply', 'payments-op'),
    ]);
    expect(rows).toHaveLength(1);
    expect(rows[0]).toMatchObject({
      grantType: 'direct',
      roleId: 'payments-op',
      grantingScope: ['t', 'org', 'sub', 'env'],
    });
  });

  it('breaks a same-length tie in favour of a direct grant', () => {
    const rows = groupEffectiveGrants([
      at('inherited', ['t', 'org'])('health.view', 'a'),
      at('direct', ['t', 'org'])('health.view', 'b'),
    ]);
    expect(rows[0]).toMatchObject({ grantType: 'direct', roleId: 'b' });
  });

  it('is empty for no grants', () => {
    expect(groupEffectiveGrants([])).toEqual([]);
  });
});

describe('firstDeniedPermissionId', () => {
  it('names a catalog permission the principal does not hold', () => {
    const denied = firstDeniedPermissionId(['instance.view']);
    expect(denied).toBeDefined();
    expect(denied).not.toBe('instance.view');
    expect(PERMISSION_CATALOG.some((entry) => entry.id === denied)).toBe(true);
  });

  it('returns undefined when every catalog permission is granted', () => {
    expect(firstDeniedPermissionId(PERMISSION_CATALOG.map((entry) => entry.id))).toBeUndefined();
  });
});
