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

describe('File Mesh fixture instances (FE-D55)', () => {
  it('a source instance renders only its own components, never the sink', () => {
    expect(selectionFor('fm-source-1')).toEqual(['file-source', 'spring-cloud-stream']);
  });

  it('a sink instance renders only its own components, never the source', () => {
    expect(selectionFor('fm-sink-1')).toEqual(['file-sink', 'spring-cloud-stream']);
  });

  it('an unbound cache instance still renders every declared component (the fallback, FE-149)', () => {
    expect(selectionFor('cache-prod-01')).toEqual(['cache-engine']);
  });
});
