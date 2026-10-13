import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { createCpClient } from '@shared/api/cpClient';

import { renderWithProviders, waitFor } from '../../../test/renderWithProviders';

import { SolutionsPage } from './SolutionsPage';
import { createSolutionsRepository, type SolutionsRepository } from './solutionsRepository';

const modes = ['light', 'dark'] as const;

describe('SolutionsPage a11y', () => {
  it.each(modes)('populated catalog — signed, partial and unsigned cards — has no violations in %s mode', async (mode) => {
    const repository = createSolutionsRepository(createCpClient('/api'));
    const { container, getByRole } = renderWithProviders(<SolutionsPage repository={repository} />, {
      mode,
    });

    await waitFor(() => {
      expect(getByRole('heading', { name: 'Solutions' })).not.toBeNull();
    });

    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('empty state has no violations in %s mode', async (mode) => {
    const repository: SolutionsRepository = {
      list: () => Promise.resolve([]),
      getPackage: () => Promise.reject(new Error('not used in this test')),
      load: () =>
        Promise.resolve({
          solutionId: 'widget-mesh',
          packageVersion: [1],
          signature: 'unsigned',
          stateVersion: 1,
          correlationId: 'corr-load-a11y',
        }),
    };
    const { container, getByText } = renderWithProviders(<SolutionsPage repository={repository} />, {
      mode,
    });

    await waitFor(() => {
      expect(getByText('No solution packages loaded')).not.toBeNull();
    });

    expect(await axe(container)).toHaveNoViolations();
  });
});
