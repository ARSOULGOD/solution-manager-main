import { describe, expect, it } from 'vitest';

import { createCpClient } from '@shared/api/cpClient';

import { createSolutionsRepository } from './solutionsRepository';

const cpClient = createCpClient('/api');

describe('createSolutionsRepository (fixture-backed, real console.yaml shapes)', () => {
  it('lists the catalog', async () => {
    const repo = createSolutionsRepository(cpClient);

    const items = await repo.list();

    expect(items.length).toBeGreaterThan(0);
    expect(items.some((item) => item.solutionId === 'cache')).toBe(true);
  });

  describe('getPackage (D-4, getSolutionPackage)', () => {
    it('unwraps SolutionPackageDocument to the raw package, for a numeric packageVersion', async () => {
      const repo = createSolutionsRepository(cpClient);

      const pkg = await repo.getPackage('cache', 7);

      expect(pkg.solution).toBe('cache');
      expect(Array.isArray(pkg.components)).toBe(true);
    });

    it('rejects for a solutionId the fixture catalog has no package for', async () => {
      const repo = createSolutionsRepository(cpClient);

      await expect(repo.getPackage('never-loaded', 1)).rejects.toThrow();
    });
  });
});
