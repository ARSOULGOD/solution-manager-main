import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { CACHE_INSTANCES } from '@shared/testdoubles/data/instances';

import { renderWithProviders } from '../../../test/renderWithProviders';

import { BulkRestartDialog } from './BulkRestartDialog';

const modes = ['light', 'dark'] as const;

describe('BulkRestartDialog a11y', () => {
  it.each(modes)('has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <BulkRestartDialog
        open
        instances={CACHE_INSTANCES.slice(0, 2)}
        onConfirm={() => undefined}
        onCancel={() => undefined}
      />,
      { mode },
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
