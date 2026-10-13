import userEvent from '@testing-library/user-event';
import { axe } from 'jest-axe';
import { HttpResponse, http } from 'msw';
import { describe, expect, it, vi } from 'vitest';

import { createConsoleClient } from '@shared/api';
import { server } from '@shared/testdoubles/server';

import { renderWithProviders, screen, waitFor } from '../../../test/renderWithProviders';
import { MetricsProvider } from '../MetricsProvider';

import { DashboardView } from './DashboardView';

// See `DashboardView.test.tsx`'s own comment — `DashboardGrid` (task 086)
// reaches a real `ChartRenderer` for the fixture's `mark: 'line'` widget.
vi.mock('echarts', () => ({ init: vi.fn(() => ({ setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn() })) }));

const consoleClient = createConsoleClient('/api');
const modes = ['light', 'dark'] as const;

function renderDashboard(landing: boolean, mode: (typeof modes)[number]) {
  return renderWithProviders(
    <MetricsProvider consoleClient={consoleClient}>
      <DashboardView
        solutionId="cache"
        instanceId="cache-prod-01"
        dashboardId="db_cache-prod-01"
        landing={landing}
      />
    </MetricsProvider>,
    { mode },
  );
}

describe('DashboardView a11y', () => {
  it.each(modes)('landing, loaded state has no violations in %s mode', async (mode) => {
    const { container } = renderDashboard(true, mode);
    await waitFor(() => {
      expect(screen.getByText('Hit rate')).toBeInTheDocument();
    });
    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('standalone (non-landing) route has no violations in %s mode', async (mode) => {
    const { container } = renderDashboard(false, mode);
    await waitFor(() => {
      expect(screen.getByText('Hit rate')).toBeInTheDocument();
    });
    expect(await axe(container)).toHaveNoViolations();
  });

  it('first-run state (zero widgets, task 089) has no violations', async () => {
    server.use(
      http.get('*/console/v1/dashboards/:dashboardId', ({ params }) =>
        HttpResponse.json({ id: String(params['dashboardId']), schemaVersion: 1, widgets: [], layout: [] }),
      ),
    );
    const { container } = renderDashboard(true, 'light');
    await waitFor(() => {
      expect(screen.getByText('No dashboards saved yet')).toBeInTheDocument();
    });
    expect(await axe(container)).toHaveNoViolations();
  });

  it('layer-filtered-to-empty state has no violations', async () => {
    server.use(
      http.get('*/console/v1/dashboards/:dashboardId', ({ params }) =>
        HttpResponse.json({
          id: String(params['dashboardId']),
          schemaVersion: 1,
          widgets: [
            {
              schemaVersion: 1,
              id: 'w1',
              title: 'Hit rate',
              mark: 'line',
              options: {},
              query: {
                metric: 'cache.hit_rate',
                selector: { entityType: 'cache-engine', instanceIds: [] },
                seriesBy: 'instance',
                aggregation: { acrossReplicas: 'auto', acrossInstances: 'none' },
              },
              range: { kind: 'preset', preset: '24h' },
              resolution: 'auto',
              axes: { y: { label: '', unitFormat: 'auto', min: null, max: null } },
              legend: { visible: true, position: 'bottom', maxHeightRows: 4 },
              series: { colorStrategy: 'palette', colors: {} },
              thresholds: [],
              refresh: { intervalSeconds: 60 },
              layer: 'product-default',
            },
          ],
          layout: [{ widgetId: 'w1', x: 0, y: 0, w: 8, h: 1, order: 0 }],
        }),
      ),
    );
    const { container } = renderDashboard(true, 'light');
    await waitFor(() => {
      expect(screen.getByText('Hit rate')).toBeInTheDocument();
    });

    const user = userEvent.setup();
    await user.click(screen.getByLabelText('Layer'));
    await user.click(await screen.findByRole('option', { name: 'Shared' }));

    await waitFor(() => {
      expect(screen.getByText('No widgets saved yet')).toBeInTheDocument();
    });
    expect(await axe(container)).toHaveNoViolations();
  });

  it('error state has no violations', async () => {
    server.use(
      http.get('*/console/v1/dashboards/:dashboardId', () =>
        HttpResponse.json({ code: 'INTERNAL', message: 'boom' }, { status: 500 }),
      ),
    );
    const { container } = renderDashboard(true, 'light');
    await waitFor(() => {
      expect(screen.getByText('Dashboard failed to load')).toBeInTheDocument();
    });
    expect(await axe(container)).toHaveNoViolations();
  });
});
