import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { createCpClient } from '@shared/api/cpClient';
import { createDpClient } from '@shared/api/dpClient';
import type { HierarchyRepository } from '@shared/hierarchy';
import type { Env } from '@shared/testdoubles/data/instances';

import { setViewportWidth } from '../../../test/matchMedia';
import { renderWithProviders, waitFor } from '../../../test/renderWithProviders';
import { createSolutionsRepository } from '../solutions';

import { InstancesPage } from './InstancesPage';
import { createInstancesRepository, type InstancesRepository } from './instancesRepository';

const HIERARCHY: HierarchyRepository = {
  organizations: () => Promise.resolve(['Acme Financial']),
  subOrgs: () => Promise.resolve(['Payments']),
  environments: () => Promise.resolve(['PROD'] as Env[]),
};

const modes = ['light', 'dark'] as const;

describe('InstancesPage a11y', () => {
  // These four cases are the most expensive in the suite — a real 12-row
  // DataGrid rendered and axe-scanned, in both themes, as grid and as card.
  //
  // They used to carry a `15000` per-test override, added when Vitest's
  // default was 5000 ms, so at the time it was a RAISE. `FE-098` later set
  // `testTimeout: 20_000` on the `a11y` project for exactly this reason
  // (`vitest.config.ts`), which silently turned the override into a
  // *lowering* — the only downward override in the suite, sitting on its
  // four slowest tests. Measured under the full suite before removal:
  // 14.96 s, 18.45 s, 15.29 s and 12.97 s against that 15 s budget. One
  // passed with 40 ms to spare; two did not pass at all (issue #131).
  //
  // The override is gone, so these inherit the project's 20 s. The other
  // half of the fix is `maxWorkers: '50%'` — see `vitest.config.ts`, which
  // carries the measurements.
  it.each(modes)('populated grid has no violations in %s mode', async (mode) => {
    const repository = createInstancesRepository(createDpClient('/api'));
    const solutionsRepository = createSolutionsRepository(createCpClient('/api'));

    const { container, getByText } = renderWithProviders(
      <InstancesPage
        solutionId="cache"
        repository={repository}
        solutionsRepository={solutionsRepository}
        hierarchyRepository={HIERARCHY}
      />,
      { mode },
    );

    await waitFor(() => {
      expect(getByText('cache-prod-01')).not.toBeNull();
    });

    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)(
    'populated card view (below md, task 107) has no violations in %s mode',
    async (mode) => {
      setViewportWidth(320);
      const repository = createInstancesRepository(createDpClient('/api'));
      const solutionsRepository = createSolutionsRepository(createCpClient('/api'));

      const { container, getByText } = renderWithProviders(
        <InstancesPage
          solutionId="cache"
          repository={repository}
          solutionsRepository={solutionsRepository}
          hierarchyRepository={HIERARCHY}
        />,
        { mode },
      );

      await waitFor(() => {
        expect(getByText('cache-prod-01')).not.toBeNull();
      });

      expect(await axe(container)).toHaveNoViolations();
    },
  );

  it.each(modes)('empty state has no violations in %s mode', async (mode) => {
    const repository: InstancesRepository = {
      list: () => Promise.resolve({ items: [], total: 0 }),
      get: () => Promise.reject(new Error('not used')),
      declare: () => Promise.reject(new Error('not used')),
      undeclare: () => Promise.reject(new Error('not used')),
    };
    const solutionsRepository = createSolutionsRepository(createCpClient('/api'));

    const { container, getByText } = renderWithProviders(
      <InstancesPage
        solutionId="cache"
        repository={repository}
        solutionsRepository={solutionsRepository}
        hierarchyRepository={HIERARCHY}
      />,
      { mode },
    );

    await waitFor(() => {
      expect(getByText('No instances declared yet')).not.toBeNull();
    });

    expect(await axe(container)).toHaveNoViolations();
  });
});
