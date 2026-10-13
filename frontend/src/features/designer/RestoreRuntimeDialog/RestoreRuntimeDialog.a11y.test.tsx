import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { renderWithProviders } from '../../../test/renderWithProviders';
import type { ConfigRepository } from '../configRepository';

import { RestoreRuntimeDialog } from './RestoreRuntimeDialog';

const repository: ConfigRepository = {
  loadDesigner: () => Promise.reject(new Error('not exercised in a11y assertions')),
  save: () => Promise.reject(new Error('not exercised in a11y assertions')),
  history: () => Promise.resolve([]),
  setSecret: () => Promise.reject(new Error('not exercised in a11y assertions')),
  acceptRuntime: () => Promise.reject(new Error('not exercised in a11y assertions')),
  restoreRuntime: () => Promise.reject(new Error('not exercised in a11y assertions')),
};

const modes = ['light', 'dark'] as const;

describe('RestoreRuntimeDialog a11y', () => {
  it.each(modes)('has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <RestoreRuntimeDialog
        open
        instanceId="cache-prod-01"
        placementId="cache-prod-01-placement-0"
        declaredVersion="2.4.0"
        repository={repository}
        onCancel={() => undefined}
        onRestored={() => undefined}
      />,
      { mode },
    );

    expect(await axe(container)).toHaveNoViolations();
  });
});
