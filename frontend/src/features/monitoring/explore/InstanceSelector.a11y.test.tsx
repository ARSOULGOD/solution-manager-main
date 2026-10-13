import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { renderWithProviders } from '../../../test/renderWithProviders';

import { InstanceSelector, type InstanceSelectorInstance } from './InstanceSelector';

const INSTANCES: readonly InstanceSelectorInstance[] = [
  { id: 'cache-prod-01', label: 'cache-prod-01', replicaCount: 3 },
  { id: 'cache-prod-02', label: 'cache-prod-02', replicaCount: 2 },
];

const modes = ['light', 'dark'] as const;

describe('InstanceSelector a11y', () => {
  it.each(modes)('has no violations below the cap, in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <InstanceSelector
        instances={INSTANCES}
        selected={['cache-prod-01']}
        onSelectedChange={() => {}}
        seriesBy="instance"
        cap={10}
        onNarrowByLabel={() => {}}
        onAggregate={() => {}}
      />,
      { mode },
    );
    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('has no violations at the cap, with the two-routes panel shown, in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <InstanceSelector
        instances={INSTANCES}
        selected={['cache-prod-01']}
        onSelectedChange={() => {}}
        seriesBy="replica"
        cap={3}
        onNarrowByLabel={() => {}}
        onAggregate={() => {}}
      />,
      { mode },
    );
    expect(await axe(container)).toHaveNoViolations();
  });

  it('has no violations while disabled (gate-locked)', async () => {
    const { container } = renderWithProviders(
      <InstanceSelector
        instances={INSTANCES}
        selected={[]}
        onSelectedChange={() => {}}
        seriesBy="instance"
        cap={10}
        disabled
        onNarrowByLabel={() => {}}
        onAggregate={() => {}}
      />,
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
