import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { useOutcomeStore } from '@shared/ui';

import { renderWithProviders } from '../../test/renderWithProviders';

import { InstanceHeader } from './InstanceHeader';

const modes = ['light', 'dark'] as const;

describe('InstanceHeader a11y', () => {
  it.each(modes)('has no violations in %s mode', async (mode) => {
    useOutcomeStore.setState({ byResource: {} });
    const { container } = renderWithProviders(
      <InstanceHeader solutionId="cache" instanceId="cache-prod-01" name="cache-prod-01" environmentName="PROD">
        body
      </InstanceHeader>,
      { mode },
    );
    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('with an outcome badge has no violations in %s mode', async (mode) => {
    useOutcomeStore.setState({
      byResource: { 'cache-prod-01': { title: 'Config applied', correlationId: 'corr-1', severity: 'ok' } },
    });
    const { container } = renderWithProviders(
      <InstanceHeader solutionId="cache" instanceId="cache-prod-01" name="cache-prod-01" environmentName="PROD">
        body
      </InstanceHeader>,
      { mode },
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
