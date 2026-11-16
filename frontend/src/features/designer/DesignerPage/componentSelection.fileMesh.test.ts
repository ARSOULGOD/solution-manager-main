/**
 * `FE-D55` exercised end to end through the fixture layer (plan 129, task
 * 154): a real fixture instance's `componentTypes`, fed through
 * `packageForSolution` and `deriveComponentSelection`, isolates the File
 * Mesh source from the sink. Not a synthetic package — the generated File Mesh package
 * (commit `c97df91`), which replaced the hand-authored `COMP_SOURCE`/`COMP_SINK` one. The
 * stream bindings now live in the shared `spring-cloud-stream` component.
 */
import { describe, expect, it } from 'vitest';

import { CACHE_INSTANCES, FILE_MESH_INSTANCES } from '@shared/testdoubles/data/instances';
import { packageForSolution } from '@shared/testdoubles/data/packages';

import { deriveComponentSelection } from './componentSelection';

const ALL_INSTANCES = [...CACHE_INSTANCES, ...FILE_MESH_INSTANCES];

function selectionFor(instanceId: string): readonly string[] {
  const instance = ALL_INSTANCES.find((i) => i.id === instanceId);
  if (instance === undefined) throw new Error(`fixture instance ${instanceId} not found`);
  const pkg = packageForSolution(instance.solutionId);
  if (pkg === undefined) throw new Error(`no package for ${instance.solutionId}`);
  return deriveComponentSelection(pkg, instance.packageVersion, instance.componentTypes).map(
    (entry) => entry.type,
  );
}

// Every shared (`role: none`) component the package declares at version 1, in
// package order. `cache` is shared too but declares versions 2-3 only, so the
// v1 fixture instances do not render it.
const SHARED_AT_V1 = [
  'server',
  'spring-boot-admin',
  'spring-cloud-stream',
  'solace',
  'application',
  'logging',
  'management',
];

describe('File Mesh fixture instances (FE-D55)', () => {
  it('a source instance renders its role component and every shared one, never the sink', () => {
    const selection = selectionFor('fm-source-1');
    expect(selection).toEqual(['file-source', ...SHARED_AT_V1]);
    expect(selection).not.toContain('file-sink');
  });

  it('a sink instance renders its role component and every shared one, never the source', () => {
    const selection = selectionFor('fm-sink-1');
    expect(selection).toEqual(['file-sink', ...SHARED_AT_V1]);
    expect(selection).not.toContain('file-source');
  });

  it('an unbound cache instance still renders every declared component (the fallback, FE-149)', () => {
    expect(selectionFor('cache-prod-01')).toEqual(['cache-engine']);
  });
});
