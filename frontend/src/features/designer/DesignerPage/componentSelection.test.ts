import { describe, expect, it } from 'vitest';

import type { ComponentDeclaration, SolutionPackage } from '@contracts/view-model/types';

import { deriveComponentSelection } from './componentSelection';

function pkg(): SolutionPackage {
  const components: ComponentDeclaration[] = [
    {
      type: 'COMP_SOURCE',
      cardinality: 'one-of-many',
      role: 'source',
      configPath: 'source',
      loggerPrefix: 'x',
      fields: [],
    },
    {
      type: 'COMP_SINK',
      cardinality: 'one-of-many',
      role: 'sink',
      version: [2],
      configPath: 'sink',
      loggerPrefix: 'y',
      fields: [],
    },
  ];
  return {
    solution: 'file-mesh',
    packageVersion: [1, 2],
    appliesTo: '>=1.0 <3.0',
    provenance: { author: 'acme', signature: 'test-fixture' },
    authoring: { reviewedRows: 2, totalRows: 2 },
    components,
  };
}

describe('deriveComponentSelection', () => {
  it('returns one entry per component, instanceId equal to type (FE-D35: exactly-one, never invented)', () => {
    expect(deriveComponentSelection(pkg(), 1)).toEqual([
      { type: 'COMP_SOURCE', instanceId: 'COMP_SOURCE', role: 'source' },
    ]);
  });

  it('excludes a component not declared for the instance version', () => {
    const selection = deriveComponentSelection(pkg(), 1);
    expect(selection.some((entry) => entry.type === 'COMP_SINK')).toBe(false);
  });

  it('includes a version-scoped component once its version is selected', () => {
    const selection = deriveComponentSelection(pkg(), 2);
    expect(selection.map((entry) => entry.type)).toEqual(['COMP_SOURCE', 'COMP_SINK']);
  });

  it('a component with no version array belongs to every version', () => {
    expect(deriveComponentSelection(pkg(), 1).map((e) => e.type)).toContain('COMP_SOURCE');
    expect(deriveComponentSelection(pkg(), 2).map((e) => e.type)).toContain('COMP_SOURCE');
  });

  it('returns empty when nothing in the package applies to the version', () => {
    const soloV3: SolutionPackage = {
      ...pkg(),
      packageVersion: [1, 2, 3],
      components: pkg().components.map((c) => ({ ...c, version: [1] })),
    };
    expect(deriveComponentSelection(soloV3, 3)).toEqual([]);
  });

  describe('an instance bound to its component types (FE-D55)', () => {
    it('returns only the components the instance binds to', () => {
      expect(deriveComponentSelection(pkg(), 2, ['COMP_SINK'])).toEqual([
        { type: 'COMP_SINK', instanceId: 'COMP_SINK', role: 'sink' },
      ]);
    });

    it('keeps package declaration order regardless of the order the binding lists', () => {
      expect(
        deriveComponentSelection(pkg(), 2, ['COMP_SINK', 'COMP_SOURCE']).map((e) => e.type),
      ).toEqual(['COMP_SOURCE', 'COMP_SINK']);
    });

    it('falls back to every component when the instance declares no binding', () => {
      expect(deriveComponentSelection(pkg(), 2, undefined).map((e) => e.type)).toEqual([
        'COMP_SOURCE',
        'COMP_SINK',
      ]);
    });

    it('is byte-identical to the two-argument call when unbound — the backward-compat guarantee', () => {
      expect(deriveComponentSelection(pkg(), 2, undefined)).toEqual(
        deriveComponentSelection(pkg(), 2),
      );
    });

    it('still honours the version filter when a binding names an out-of-version component', () => {
      expect(deriveComponentSelection(pkg(), 1, ['COMP_SINK'])).toEqual([]);
    });

    it('ignores a bound type the package does not declare rather than inventing an entry', () => {
      expect(deriveComponentSelection(pkg(), 2, ['NOT_IN_PACKAGE'])).toEqual([]);
    });

    it('an empty binding selects nothing — distinct from an absent one, which selects everything', () => {
      expect(deriveComponentSelection(pkg(), 2, [])).toEqual([]);
    });
  });

  it('filters by the persisted package-defined role while retaining shared components', () => {
    const withShared: SolutionPackage = {
      ...pkg(),
      components: [
        ...pkg().components,
        { type: 'SHARED', cardinality: 'one-of-many', configPath: 'shared', loggerPrefix: 's', fields: [] },
      ],
    };
    expect(deriveComponentSelection(withShared, 2, undefined, 'sink').map((entry) => entry.type)).toEqual([
      'COMP_SINK',
      'SHARED',
    ]);
  });
});
