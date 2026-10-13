import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { renderWithProviders } from '../../../test/renderWithProviders';
import type { ConfigRepository } from '../configRepository';

import { AcceptRuntimeDialog } from './AcceptRuntimeDialog';

const repository: ConfigRepository = {
  loadDesigner: () => Promise.reject(new Error('not exercised in a11y assertions')),
  save: () => Promise.reject(new Error('not exercised in a11y assertions')),
  history: () => Promise.resolve([]),
  setSecret: () => Promise.reject(new Error('not exercised in a11y assertions')),
  acceptRuntime: () =>
    Promise.resolve({
      stateVersion: 5,
      generation: 5,
      unchanged: false,
      placementId: 'cache-prod-01-placement-0',
      fileBytesDifferFromRendered: false,
      truncated: false,
      notAccepted: [{ path: 'cache-engine.oldKey', reason: 'undeclared-key' }],
    }),
  restoreRuntime: () => Promise.reject(new Error('not exercised in a11y assertions')),
};

const modes = ['light', 'dark'] as const;

describe('AcceptRuntimeDialog a11y', () => {
  it.each(modes)('has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <AcceptRuntimeDialog
        open
        instanceId="cache-prod-01"
        placementId="cache-prod-01-placement-0"
        baseStateVersion={4}
        repository={repository}
        onClose={() => undefined}
        onAccepted={() => undefined}
      />,
      { mode },
    );

    expect(await axe(container)).toHaveNoViolations();
  });
});
