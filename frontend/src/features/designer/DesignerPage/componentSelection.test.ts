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
});
