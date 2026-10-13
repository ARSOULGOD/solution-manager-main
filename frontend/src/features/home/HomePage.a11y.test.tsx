import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { createCpClient } from '@shared/api/cpClient';
import { createDpClient } from '@shared/api/dpClient';

import { renderWithProviders, waitFor } from '../../test/renderWithProviders';

import { HomePage } from './HomePage';
import { createHomeRepository } from './homeRepository';

const modes = ['light', 'dark'] as const;

describe('HomePage a11y', () => {
  it.each(modes)('populated state has no violations in %s mode', async (mode) => {
    const repository = createHomeRepository(createCpClient('/api'), createDpClient('/api'));
    const { container, getByRole } = renderWithProviders(<HomePage repository={repository} />, {
      mode,
    });

    await waitFor(() => {
      expect(getByRole('heading', { name: 'Home' })).not.toBeNull();
    });

    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('first-run state has no violations in %s mode', async (mode) => {
    const repository = {
      summary: () =>
        Promise.resolve({
          solutionsManaged: 0,
          instancesDeclared: 0,
          needAttentionNow: null,
          configStagedNotApplied: null,
        }),
      attention: () => Promise.resolve([]),
    };
    const { container, getByText } = renderWithProviders(<HomePage repository={repository} />, {
      mode,
    });

    await waitFor(() => {
      expect(getByText('Load a Solution Package to begin')).not.toBeNull();
    });

    expect(await axe(container)).toHaveNoViolations();
  });
});
