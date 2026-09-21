import { describe, expect, it } from 'vitest';

import { SOLUTIONS } from '@shared/testdoubles/data/solutions';

import { countDistinctSolutions, toSidebarSolutions } from './sidebarSolutions';

describe('toSidebarSolutions', () => {
  it('omits dev-mode packages and carries id, name, instance count and glyph', () => {
    expect(toSidebarSolutions(SOLUTIONS)).toEqual([
      { id: 'cache', name: 'Cache Engine', instanceCount: 12, icon: 'bolt' },
      { id: 'filemesh', name: 'File Mesh', instanceCount: 0, icon: 'files' },
    ]);
  });

  it('is empty for an empty catalog', () => {
    expect(toSidebarSolutions([])).toEqual([]);
  });
});

describe('countDistinctSolutions', () => {
  it('counts solutions, not packages — two cache packages are one solution', () => {
    expect(SOLUTIONS.filter((pkg) => pkg.solution === 'cache')).toHaveLength(2);
    expect(countDistinctSolutions(SOLUTIONS)).toBe(2);
  });

  it('is zero for an empty catalog', () => {
    expect(countDistinctSolutions([])).toBe(0);
  });
});
