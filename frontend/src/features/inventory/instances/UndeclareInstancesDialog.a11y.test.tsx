import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { CACHE_INSTANCES } from '@shared/testdoubles/data/instances';

import { renderWithProviders } from '../../../test/renderWithProviders';

import { UndeclareInstancesDialog } from './UndeclareInstancesDialog';

const modes = ['light', 'dark'] as const;

describe('UndeclareInstancesDialog a11y', () => {
  it.each(modes)('has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <UndeclareInstancesDialog
        open
        instances={CACHE_INSTANCES.slice(0, 2)}
        onConfirm={() => undefined}
        onCancel={() => undefined}
      />,
      { mode },
    );
    expect(await axe(container)).toHaveNoViolations();
  });

  it('has no violations while reporting a partial failure', async () => {
    const { container } = renderWithProviders(
      <UndeclareInstancesDialog
        open
        instances={CACHE_INSTANCES.slice(0, 2)}
        onConfirm={() => undefined}
        onCancel={() => undefined}
        errorMessage="Still declared: cache-prod-02."
      />,
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
