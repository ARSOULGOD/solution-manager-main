import userEvent from '@testing-library/user-event';
import { HttpResponse, http } from 'msw';
import { describe, expect, it } from 'vitest';

import { createConsoleClient } from '@shared/api';
import { CACHE_INSTANCES } from '@shared/testdoubles/data/instances';
import { METRIC_CATALOG } from '@shared/testdoubles/data/metrics';
import { server } from '@shared/testdoubles/server';

import { renderWithProviders, screen, waitFor } from '../../../test/renderWithProviders';
import { MetricsProvider } from '../MetricsProvider';

import { MonitoringFirstRun } from './MonitoringFirstRun';

const consoleClient = createConsoleClient('/api');
const totalReplicas = CACHE_INSTANCES.reduce((sum, instance) => sum + instance.replicas.length, 0);

function renderFirstRun() {
  return renderWithProviders(
    <MetricsProvider consoleClient={consoleClient}>
      <MonitoringFirstRun solutionId="cache" instanceId="cache-prod-01" />
    </MetricsProvider>,
  );
}

describe('MonitoringFirstRun', () => {
  it('states the empty console does not show an empty grid', () => {
    renderFirstRun();
    expect(screen.getByText('No dashboards saved yet')).toBeInTheDocument();
    expect(
      screen.getByText(/The empty console does not show an empty grid/),
    ).toBeInTheDocument();
  });

  it('shows one tile group per entity type with instance, replica and metric counts', async () => {
    renderFirstRun();
    expect(await screen.findByText('Cache instance')).toBeInTheDocument();
    await waitFor(() => {
      expect(screen.getByText(String(CACHE_INSTANCES.length))).toBeInTheDocument();
    });
    expect(screen.getByText(String(totalReplicas))).toBeInTheDocument();
    expect(screen.getByText(String(METRIC_CATALOG.length))).toBeInTheDocument();
    expect(screen.getByText('replicas')).toBeInTheDocument();
    expect(screen.getByText('metrics')).toBeInTheDocument();
  });

  it('offers exactly one primary action, into Explore', async () => {
    const user = userEvent.setup();
    renderFirstRun();
    const actions = screen.getAllByRole('button', { name: 'Open Explore' });
    expect(actions).toHaveLength(1);
    await user.click(actions[0]!);
  });

  it('renders no entity-type tiles when the catalog reports none, but still offers the action', () => {
    server.use(http.get('*/console/v1/entity-types', () => HttpResponse.json([])));
    renderFirstRun();
    expect(screen.getByRole('button', { name: 'Open Explore' })).toBeInTheDocument();
    expect(screen.queryByText('Cache instance')).toBeNull();
  });
});
