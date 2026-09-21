import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { CACHE_INSTANCES } from '@shared/testdoubles/data/instances';
import { useOutcomeStore } from '@shared/ui';

import { renderWithProviders } from '../../test/renderWithProviders';

import { InstanceHeader } from './InstanceHeader';

const healthy = CACHE_INSTANCES.find((row) => row.id === 'cache-prod-01');
const drifted = CACHE_INSTANCES.find((row) => row.id === 'cache-prod-02');

if (healthy === undefined || drifted === undefined) {
  throw new Error('InstanceHeader.a11y.test.tsx: expected fixture rows are missing');
}

const modes = ['light', 'dark'] as const;

describe('InstanceHeader a11y', () => {
  it.each(modes)('healthy instance has no violations in %s mode', async (mode) => {
    useOutcomeStore.setState({ byResource: {} });
    const { container } = renderWithProviders(
      <InstanceHeader solutionId="cache" instance={healthy}>
        body
      </InstanceHeader>,
      { mode },
    );
    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('blocked (drift) instance with an outcome badge has no violations in %s mode', async (mode) => {
    useOutcomeStore.setState({
      byResource: { 'cache-prod-02': { title: 'Config applied', correlationId: 'corr-1', severity: 'ok' } },
    });
    const { container } = renderWithProviders(
      <InstanceHeader solutionId="cache" instance={drifted}>
        body
      </InstanceHeader>,
      { mode },
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
