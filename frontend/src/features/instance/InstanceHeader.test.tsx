import { act } from 'react';
import { describe, expect, it } from 'vitest';

import { pushOutcome, useOutcomeStore } from '@shared/ui';

import { renderWithProviders, screen } from '../../test/renderWithProviders';

import { InstanceHeader } from './InstanceHeader';

describe('InstanceHeader', () => {
  it('renders the instance name and env chip', () => {
    renderWithProviders(
      <InstanceHeader solutionId="cache" instanceId="cache-prod-01" name="cache-prod-01" environmentName="PROD">
        body
      </InstanceHeader>,
    );

    expect(screen.getByRole('heading', { name: 'cache-prod-01' })).not.toBeNull();
    expect(screen.getByText('PROD')).not.toBeNull();
  });

  it('renders no env chip while the environment name has not resolved yet', () => {
    renderWithProviders(
      <InstanceHeader solutionId="cache" instanceId="cache-prod-01" name="cache-prod-01">
        body
      </InstanceHeader>,
    );

    expect(screen.getByRole('heading', { name: 'cache-prod-01' })).not.toBeNull();
    expect(screen.queryByText('PROD')).toBeNull();
  });

  it('renders Designer | Governance | Monitoring as route links', () => {
    renderWithProviders(
      <InstanceHeader solutionId="cache" instanceId="cache-prod-01" name="cache-prod-01" environmentName="PROD">
        body
      </InstanceHeader>,
    );

    const tablist = screen.getByRole('tablist');
    expect(tablist).not.toBeNull();
    expect(screen.getByRole('tab', { name: 'Designer' }).getAttribute('href')).toBe(
      '/solutions/cache/instances/cache-prod-01/designer',
    );
    expect(screen.getByRole('tab', { name: 'Governance' }).getAttribute('href')).toBe(
      '/solutions/cache/instances/cache-prod-01/governance',
    );
    expect(screen.getByRole('tab', { name: 'Monitoring' }).getAttribute('href')).toBe(
      '/solutions/cache/instances/cache-prod-01/monitoring',
    );
  });

  it('shows the persistent outcome badge (with its correlation id) once one is pushed for this instance', () => {
    useOutcomeStore.setState({ byResource: {} });
    act(() => {
      pushOutcome('cache-prod-01', {
        title: 'Config applied',
        correlationId: 'corr-abc',
        severity: 'ok',
      });
    });

    renderWithProviders(
      <InstanceHeader solutionId="cache" instanceId="cache-prod-01" name="cache-prod-01" environmentName="PROD">
        body
      </InstanceHeader>,
    );

    expect(screen.getByText('Config applied')).not.toBeNull();
    expect(screen.getByText('Correlation id corr-abc')).not.toBeNull();
  });

  it('renders no outcome badge for an instance with no recorded outcome', () => {
    useOutcomeStore.setState({ byResource: {} });

    renderWithProviders(
      <InstanceHeader solutionId="cache" instanceId="cache-prod-01" name="cache-prod-01" environmentName="PROD">
        body
      </InstanceHeader>,
    );

    expect(document.querySelector('[data-outcome-badge]')).toBeNull();
  });
});
