import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { DIVERGED_REPLICAS, ROLLOUT_REPLICAS } from '@shared/testdoubles/data/designer';

import { renderWithProviders } from '../../../test/renderWithProviders';

import { RolloutCard } from './RolloutCard';

const modes = ['light', 'dark'] as const;

describe('RolloutCard a11y', () => {
  it.each(modes)('collapsed has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <RolloutCard replicas={ROLLOUT_REPLICAS} reached="restart-observed" diverged={false} />,
      { mode },
    );
    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('expanded (non-diverged) has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <RolloutCard
        replicas={ROLLOUT_REPLICAS}
        reached="restart-observed"
        diverged={false}
        defaultOpen
      />,
      { mode },
    );
    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('expanded and diverged has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <RolloutCard replicas={DIVERGED_REPLICAS} reached="restart-observed" diverged defaultOpen />,
      { mode },
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
