import { act } from 'react';
import { describe, expect, it } from 'vitest';

import { CACHE_INSTANCES } from '@shared/testdoubles/data/instances';
import { pushOutcome, useOutcomeStore } from '@shared/ui';

import { renderWithProviders, screen } from '../../test/renderWithProviders';

import { InstanceHeader } from './InstanceHeader';

const healthy = CACHE_INSTANCES.find((row) => row.id === 'cache-prod-01');
const drifted = CACHE_INSTANCES.find((row) => row.id === 'cache-prod-02');

if (healthy === undefined || drifted === undefined) {
  throw new Error('InstanceHeader.test.tsx: expected fixture rows are missing');
}

describe('InstanceHeader', () => {
  it('renders the instance name, env chip and health, with no version-blocked badge for a healthy instance', () => {
    renderWithProviders(
      <InstanceHeader solutionId="cache" instance={healthy}>
        body
      </InstanceHeader>,
    );

    expect(screen.getByRole('heading', { name: 'cache-prod-01' })).not.toBeNull();
    expect(screen.getByText('PROD')).not.toBeNull();
    expect(screen.getByText('Healthy')).not.toBeNull();
    expect(screen.queryByText('Version drift · Designer read-only')).toBeNull();
  });

  it('states, for a blocked (drift) instance, that Governance and Monitoring still work', () => {
    renderWithProviders(
      <InstanceHeader solutionId="cache" instance={drifted}>
        body
      </InstanceHeader>,
    );

    expect(screen.getByText('Version drift · Designer read-only')).not.toBeNull();
    expect(
      screen.getByText(
        'Governance and Monitoring still work — two of three categories remain available while this version state blocks configuration edits.',
      ),
    ).not.toBeNull();
  });

  it('renders Designer | Governance | Monitoring as route links', () => {
    renderWithProviders(
      <InstanceHeader solutionId="cache" instance={healthy}>
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
      <InstanceHeader solutionId="cache" instance={healthy}>
        body
      </InstanceHeader>,
    );

    expect(screen.getByText('Config applied')).not.toBeNull();
    expect(screen.getByText('Correlation id corr-abc')).not.toBeNull();
  });

  it('renders no outcome badge for an instance with no recorded outcome', () => {
    useOutcomeStore.setState({ byResource: {} });

    renderWithProviders(
      <InstanceHeader solutionId="cache" instance={healthy}>
        body
      </InstanceHeader>,
    );

    expect(document.querySelector('[data-outcome-badge]')).toBeNull();
  });
});
