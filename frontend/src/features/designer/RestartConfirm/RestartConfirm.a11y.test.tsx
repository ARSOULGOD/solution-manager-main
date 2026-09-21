import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { ROLLOUT_REPLICAS } from '@shared/testdoubles/data/designer';

import { renderWithProviders } from '../../../test/renderWithProviders';
import type { ApplyRepository } from '../applyRepository';
import { RESTART_STRATEGIES } from '../restartStrategies';

import { RestartConfirm } from './RestartConfirm';

const MANUAL = RESTART_STRATEGIES.find((s) => s.key === 'manual')!;

const repository: ApplyRepository = {
  stage: () => Promise.reject(new Error('not exercised in a11y assertions')),
  observe: () => Promise.reject(new Error('not exercised in a11y assertions')),
  rollback: () => Promise.reject(new Error('not exercised in a11y assertions')),
};

const modes = ['light', 'dark'] as const;

describe('RestartConfirm a11y', () => {
  it.each(modes)('has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <RestartConfirm
        open
        instanceId="cache-prod-01"
        strategy={MANUAL}
        replicas={ROLLOUT_REPLICAS}
        repository={repository}
        onCancel={() => undefined}
        onApplied={() => undefined}
      />,
      { mode },
    );

    expect(await axe(container)).toHaveNoViolations();
  });
});
