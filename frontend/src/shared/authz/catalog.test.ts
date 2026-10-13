import { describe, expect, it } from 'vitest';

import { PERMISSION_CATALOG, PERMISSION_GROUPS } from './catalog';
import { ALL_GRANTABLE_VERBS } from './permissions';

describe('PERMISSION_CATALOG', () => {
  it('makes every initial D10 operation grantable', () => {
    expect(PERMISSION_CATALOG.every((permission) => permission.status === 'grantable')).toBe(true);
  });

  it('instance.bindTarget and target.use are marked two-sided (§A.12.3)', () => {
    expect(PERMISSION_CATALOG.find((p) => p.id === 'instance.bindTarget')?.twoSided).toBe(true);
    expect(PERMISSION_CATALOG.find((p) => p.id === 'target.use')?.twoSided).toBe(true);
  });

  it('has no duplicate ids', () => {
    const ids = PERMISSION_CATALOG.map((p) => p.id);
    expect(new Set(ids).size).toBe(ids.length);
  });
});

describe('PERMISSION_GROUPS', () => {
  it('every group id resolves to a real catalog entry', () => {
    const catalogIds = new Set<string>(PERMISSION_CATALOG.map((p) => p.id));
    for (const group of PERMISSION_GROUPS) {
      for (const id of group.permissionIds) {
        expect(catalogIds.has(id)).toBe(true);
      }
    }
  });

  it('every catalog id appears in exactly one group', () => {
    const seen = new Map<string, number>();
    for (const group of PERMISSION_GROUPS) {
      for (const id of group.permissionIds) {
        seen.set(id, (seen.get(id) ?? 0) + 1);
      }
    }
    for (const entry of PERMISSION_CATALOG) {
      expect(seen.get(entry.id)).toBe(1);
    }
  });
});

describe('the ratified `D18` catalog', () => {
  const ids = new Set<string>(PERMISSION_CATALOG.map((p) => p.id));

  it('carries all five ratified group verbs', () => {
    for (const id of [
      'group.view',
      'group.create',
      'group.edit',
      'group.deactivate',
      'group.member.manage',
    ]) {
      expect(ids.has(id)).toBe(true);
    }
  });

  it('carries neither verb `D18` declined', () => {
    // `group.view` covers member visibility; `role.assign` covers assigning
    // a role to a group. A second spelling of either would drift.
    expect(ids.has('group.member.view')).toBe(false);
    expect(ids.has('group.role.assign')).toBe(false);
  });

  it('marks group.member.manage high-consequence — a membership row delegates roles', () => {
    expect(PERMISSION_CATALOG.find((p) => p.id === 'group.member.manage')?.highConsequence).toBe(
      true,
    );
  });

  it('is 35 operations', () => {
    expect(PERMISSION_CATALOG.length).toBe(35);
  });
});

describe('GrantableVerb', () => {
  // `ALL_GRANTABLE_VERBS` is typed `readonly GrantableVerb[]`, so an id that
  // is NOT in the union fails `npm run typecheck`. This assertion closes the
  // other direction: a catalog id missing from the union fails HERE. Before
  // both existed, the union and the catalog were two hand-maintained lists of
  // the same ids with nothing comparing them.
  it('is exactly the catalog — nothing in one list that is missing from the other', () => {
    expect([...ALL_GRANTABLE_VERBS].sort()).toEqual(PERMISSION_CATALOG.map((p) => p.id).sort());
  });
});
