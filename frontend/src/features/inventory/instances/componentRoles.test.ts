import { describe, expect, it } from 'vitest';

import type { ComponentDeclaration, SolutionPackage } from '@contracts/view-model/types';

import { deriveComponentRoleGroups, sharedComponentTypes } from './componentRoles';

function pkg(): SolutionPackage {
  const components: ComponentDeclaration[] = [
    { type: 'a-source', cardinality: 'one-of-many', role: 'source', configPath: 'a.source', loggerPrefix: 'x', fields: [] },
    { type: 'a-sink', cardinality: 'one-of-many', role: 'sink', configPath: 'a.sink', loggerPrefix: 'y', fields: [] },
    { type: 'a-sink-v2-only', cardinality: 'one-of-many', role: 'sink', version: [2], configPath: 'a.sink2', loggerPrefix: 'z', fields: [] },
    { type: 'logging', cardinality: 'zero-or-more', role: 'none', configPath: 'logging', loggerPrefix: 'l', fields: [] },
    { type: 'server', cardinality: 'zero-or-more', configPath: 'server', loggerPrefix: 's', fields: [] },
  ];
  return {
    solution: 'test',
    packageVersion: [1, 2],
    appliesTo: '>=1.0 <3.0',
    provenance: { author: 'acme', signature: 'test-fixture' },
    authoring: { reviewedRows: 5, totalRows: 5 },
    components,
  };
}

describe('deriveComponentRoleGroups', () => {
  it('groups version-eligible components by role, excluding role undefined/"none"', () => {
    expect(deriveComponentRoleGroups(pkg(), 1)).toEqual([
      { role: 'source', componentTypes: ['a-source'] },
      { role: 'sink', componentTypes: ['a-sink'] },
    ]);
  });

  it('a role with two components at one version returns both types, one group', () => {
    expect(deriveComponentRoleGroups(pkg(), 2)).toEqual([
      { role: 'source', componentTypes: ['a-source'] },
      { role: 'sink', componentTypes: ['a-sink', 'a-sink-v2-only'] },
    ]);
  });

  it('a package with zero or one non-none role returns that many groups, never a synthesized second one', () => {
    const single: SolutionPackage = {
      ...pkg(),
      components: pkg().components.filter((c) => c.role !== 'sink'),
    };
    expect(deriveComponentRoleGroups(single, 1)).toEqual([{ role: 'source', componentTypes: ['a-source'] }]);
  });

  it('treats null and blank role metadata as no role', () => {
    const noRole: SolutionPackage = {
      ...pkg(),
      components: pkg().components.map((component) => ({ ...component, role: null })),
    };
    expect(deriveComponentRoleGroups(noRole, 1)).toEqual([]);
    expect(sharedComponentTypes(noRole, 1)).toEqual(['a-source', 'a-sink', 'logging', 'server']);
  });
});

describe('sharedComponentTypes', () => {
  it('returns version-eligible components whose role is undefined or "none"', () => {
    expect(sharedComponentTypes(pkg(), 1)).toEqual(['logging', 'server']);
  });

  it('excludes a shared component declared only for another version', () => {
    const versioned: SolutionPackage = {
      ...pkg(),
      components: [...pkg().components, { type: 'v2-only-shared', cardinality: 'zero-or-more', role: 'none', version: [2], configPath: 'v2', loggerPrefix: 'v', fields: [] }],
    };
    expect(sharedComponentTypes(versioned, 1)).toEqual(['logging', 'server']);
    expect(sharedComponentTypes(versioned, 2)).toEqual(['logging', 'server', 'v2-only-shared']);
  });
});
