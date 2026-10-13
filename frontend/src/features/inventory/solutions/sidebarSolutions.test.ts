import { describe, expect, it } from 'vitest';

import { countDistinctSolutions, toSidebarSolutions } from './sidebarSolutions';
import type { SolutionCatalogItem } from './solutionsRepository';

const CACHE: SolutionCatalogItem = {
  solutionId: 'cache',
  solution: 'cache',
  name: 'Cache Engine',
  packageVersions: [6, 7],
  latestPackageVersion: 7,
  signature: 'unsigned',
  stateVersion: 1,
};

const CACHE_NIGHTLY: SolutionCatalogItem = {
  solutionId: 'cache-nightly',
  solution: 'cache',
  name: 'Cache Engine · nightly',
  packageVersions: [8],
  latestPackageVersion: 8,
  signature: 'unsigned',
  stateVersion: 1,
};

const OTHER_SOLUTION: SolutionCatalogItem = {
  solutionId: 'other',
  solution: 'other',
  name: 'Other Solution',
  packageVersions: [1],
  latestPackageVersion: 1,
  signature: 'unsigned',
  stateVersion: 1,
};

describe('toSidebarSolutions', () => {
  it('maps id and name, with a flagged (not invented) instance count and no icon', () => {
    expect(toSidebarSolutions([CACHE])).toEqual([{ id: 'cache', name: 'Cache Engine', instanceCount: 0 }]);
  });

  it('is empty for an empty catalog', () => {
    expect(toSidebarSolutions([])).toEqual([]);
  });
});

describe('countDistinctSolutions', () => {
  it('counts solutions, not packages — two cache packages are one solution', () => {
    const items = [CACHE, CACHE_NIGHTLY, OTHER_SOLUTION];
    expect(items.filter((item) => item.solution === 'cache')).toHaveLength(2);
    expect(countDistinctSolutions(items)).toBe(2);
  });

  it('is zero for an empty catalog', () => {
    expect(countDistinctSolutions([])).toBe(0);
  });
});
