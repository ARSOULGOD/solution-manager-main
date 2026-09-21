import { describe, expect, it } from 'vitest';

import CACHE_PACKAGE from '@contracts/packages/cache/package.json';
import type { ComponentSelection, DraftValues, SolutionPackage } from '@contracts/view-model/types';

import { applyPackageDefaults, mergeReset } from './mergeReset';

function pkg(): SolutionPackage {
  return {
    solution: 'file-mesh',
    packageVersion: [1],
    appliesTo: '>=1.0 <2.0',
    provenance: { author: 'solace', signature: 'test-fixture' },
    authoring: { reviewedRows: 2, totalRows: 2 },
    components: [
      {
        type: 'source-grid',
        cardinality: 'one-of-many',
        configPath: 'source-grid',
        loggerPrefix: 'com.solace.solutions.filemesh.source',
        fields: [
          { key: 'ACTION', type: 'enum', options: ['download', 'deploy'], discriminator: true },
          {
            key: 'DEPLOYMENT_MODE',
            type: 'enum',
            options: ['standalone', 'highavailable'],
            when: { ACTION: 'deploy' },
            default: 'standalone',
          },
        ],
      },
    ],
  };
}

const componentSelection: ComponentSelection = [{ type: 'source-grid', instanceId: 'source-1' }];

describe('mergeReset', () => {
  it('applies next over current, per instance, without dropping a key next omits', () => {
    const current: DraftValues = { 'source-1': { ACTION: 'deploy', DEPLOYMENT_MODE: 'highavailable' } };
    const next: DraftValues = { 'source-1': { ACTION: 'download' } };

    expect(mergeReset(current, next)).toEqual({
      'source-1': { ACTION: 'download', DEPLOYMENT_MODE: 'highavailable' },
    });
  });

  it('keeps an instance current holds but next does not mention at all', () => {
    const current: DraftValues = { 'sink-1': { PATH: '/tmp' } };
    const next: DraftValues = {};

    expect(mergeReset(current, next)).toEqual({ 'sink-1': { PATH: '/tmp' } });
  });
});

describe('applyPackageDefaults', () => {
  it('fills a missing field with its declared default', () => {
    const draft: DraftValues = { 'source-1': { ACTION: 'deploy' } };

    expect(applyPackageDefaults(pkg(), componentSelection, draft, 1)).toEqual({
      'source-1': { ACTION: 'deploy', DEPLOYMENT_MODE: 'standalone' },
    });
  });

  it('never overwrites a value the draft already has, even a retained hidden-branch one', () => {
    const draft: DraftValues = { 'source-1': { ACTION: 'download', DEPLOYMENT_MODE: 'highavailable' } };

    expect(applyPackageDefaults(pkg(), componentSelection, draft, 1)).toEqual({
      'source-1': { ACTION: 'download', DEPLOYMENT_MODE: 'highavailable' },
    });
  });

  it('leaves an unselected instance in the draft untouched', () => {
    const draft: DraftValues = { 'unknown-1': { X: 1 } };

    expect(applyPackageDefaults(pkg(), [], draft, 1)).toEqual({ 'unknown-1': { X: 1 } });
  });
});

describe('applyPackageDefaults — version filtering (FE-121, FE-D37)', () => {
  const CACHE_SELECTION: ComponentSelection = [{ type: 'cache-engine', instanceId: 'cache-0' }];

  it('seeds the v6-only default and never the v7-only one at v6', () => {
    const result = applyPackageDefaults(CACHE_PACKAGE as SolutionPackage, CACHE_SELECTION, { 'cache-0': {} }, 6);

    expect(result['cache-0']).toHaveProperty('max-size-bytes', 67108864);
    expect(result['cache-0']).not.toHaveProperty('max-weight');
  });

  it('seeds the v7-only default and never the v6-only one at v7', () => {
    const result = applyPackageDefaults(CACHE_PACKAGE as SolutionPackage, CACHE_SELECTION, { 'cache-0': {} }, 7);

    expect(result['cache-0']).toHaveProperty('max-weight', 536870912);
    expect(result['cache-0']).not.toHaveProperty('max-size-bytes');
  });

  it('a version-free default is seeded identically at v6 and v7', () => {
    const atV6 = applyPackageDefaults(CACHE_PACKAGE as SolutionPackage, CACHE_SELECTION, { 'cache-0': {} }, 6);
    const atV7 = applyPackageDefaults(CACHE_PACKAGE as SolutionPackage, CACHE_SELECTION, { 'cache-0': {} }, 7);

    expect(atV6['cache-0']).toHaveProperty('mode', 'READ');
    expect(atV7['cache-0']).toHaveProperty('mode', 'READ');
  });
});
