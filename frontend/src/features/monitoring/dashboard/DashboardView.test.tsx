import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { HttpResponse, http } from 'msw';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { createConsoleClient } from '@shared/api';
import { server } from '@shared/testdoubles/server';

import { renderWithProviders } from '../../../test/renderWithProviders';
import { MetricsProvider } from '../MetricsProvider';

import { DashboardView } from './DashboardView';

// `DashboardGrid` (task 086) now renders real `WidgetFrame`s, one of which
// (the fixture's "Hit rate", `mark: 'line'`) reaches a real `ChartRenderer`
// — mocked the same way `ExplorePage.test.tsx` mocks it, since jsdom has no
// canvas 2D context and a real `echarts.init` throws trying to use one.
vi.mock('echarts', () => ({
  init: vi.fn(() => ({ setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn() })),
}));

const consoleClient = createConsoleClient('/api');

function renderDashboard(
  overrides: Partial<{ landing: boolean; dashboardId: string; route: string }> = {},
) {
  return renderWithProviders(
    <MetricsProvider consoleClient={consoleClient}>
      <DashboardView
        solutionId="cache"
        instanceId="cache-prod-01"
        dashboardId={overrides.dashboardId ?? 'db_cache-prod-01'}
        landing={overrides.landing ?? true}
      />
    </MetricsProvider>,
    overrides.route !== undefined ? { route: overrides.route } : undefined,
  );
}

afterEach(() => {
  document.dispatchEvent(new Event('visibilitychange'));
});

describe('DashboardView', () => {
  it('renders the toolbar controls', () => {
    renderDashboard();
    expect(screen.getByLabelText('Time range')).toBeInTheDocument();
    expect(screen.getByLabelText('Refresh')).toBeInTheDocument();
    expect(screen.getByLabelText('Layer')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Auto' })).toBeInTheDocument();
  });

  it('states the dashboard’s range/refresh are authoritative over any widget default', () => {
    renderDashboard();
    expect(
      screen.getByText(/The dashboard’s range and refresh are authoritative for every widget/),
    ).toBeInTheDocument();
  });

  /**
   * Task 131 — the landing note was a permanent full-width `Alert` above
   * the toolbar. It is a fact an operator needs once, so it is now an
   * `InfoTip` on the tab bar; the tip text rides on the trigger's
   * accessible name (`shared/ui`'s `Tooltip` uses `aria-label`, see its
   * own header), which is where this asserts it.
   */
  it('offers the landing (no list route) note only when landing', () => {
    const landingView = renderDashboard({ landing: true });
    expect(
      landingView.container.querySelector(
        '[aria-label^="Monitoring renders the package’s default dashboard directly"]',
      ),
    ).not.toBeNull();

    const standaloneView = renderDashboard({ landing: false });
    expect(
      standaloneView.container.querySelector(
        '[aria-label^="Monitoring renders the package’s default dashboard directly"]',
      ),
    ).toBeNull();
  });

  it('states the dashboard-level range/refresh note once, not as a stacked banner', () => {
    const { container } = renderDashboard();

    // `DM-D21` is kept, demoted from an `Alert` to a caption under the
    // toolbar it explains — one note, not two stacked full-width banners.
    expect(
      screen.getByText(/The dashboard’s range and refresh are authoritative for every widget/),
    ).toBeInTheDocument();
    expect(container.querySelectorAll('[role="alert"]')).toHaveLength(0);
  });

  // Task 131 — moved here from `DashboardGrid.test.tsx` along with the
  // button itself, which now lives on `DashboardToolbar`.
  it('renders "Reset defaults"; clicking it calls resetDefaults for this dashboard', async () => {
    const user = userEvent.setup();
    let resetCalledFor: string | null = null;
    server.use(
      http.post('*/console/v1/dashboards/:dashboardId/reset', ({ params }) => {
        resetCalledFor = String(params['dashboardId']);
        return HttpResponse.json({
          id: String(params['dashboardId']),
          schemaVersion: 1,
          widgets: [],
          layout: [],
        });
      }),
    );
    renderDashboard();

    await user.click(screen.getByRole('button', { name: 'Reset defaults' }));

    await waitFor(() => {
      expect(resetCalledFor).toBe('db_cache-prod-01');
    });
  });

  it('toggles edit mode from the toolbar, revealing the grid’s move controls', async () => {
    const user = userEvent.setup();
    renderDashboard();
    await waitFor(() => {
      expect(screen.getByText('Hit rate')).toBeInTheDocument();
    });

    expect(screen.queryAllByRole('button', { name: 'Move earlier' })).toHaveLength(0);

    await user.click(screen.getByRole('button', { name: 'Edit layout' }));
    expect(screen.getAllByRole('button', { name: 'Move later' }).length).toBeGreaterThan(0);

    await user.click(screen.getByRole('button', { name: 'Done' }));
    expect(screen.queryAllByRole('button', { name: 'Move earlier' })).toHaveLength(0);
  });

  /**
   * Plan `126`'s second defect — the explicit front door, not just a tab.
   * Navigation is asserted through the tab bar's own selection, which
   * reads the router location: `MemoryRouter` (see `renderWithProviders`)
   * has no `window.location` to check.
   */
  it('offers "Add widget" on the toolbar, routing to Explore', async () => {
    const user = userEvent.setup();
    renderDashboard({ route: '/solutions/cache/instances/cache-prod-01/monitoring' });

    expect(screen.getByRole('tab', { name: 'Explore' }).getAttribute('aria-selected')).toBe(
      'false',
    );

    await user.click(screen.getByRole('button', { name: 'Add widget' }));

    await waitFor(() => {
      expect(screen.getByRole('tab', { name: 'Explore' }).getAttribute('aria-selected')).toBe(
        'true',
      );
    });
  });

  /**
   * Task 130 replaced the one-way "← Monitoring" link with the
   * Dashboards/Explore sub-tab bar, which routes BOTH ways from a fixed
   * place and is present on the landing view too. What these two cases
   * exist to protect — you can always get back to Monitoring, and the
   * landing view does not duplicate chrome it is already inside — is
   * asserted against the bar instead.
   */
  it('routes back to Monitoring via the Dashboards tab, not a separate back link', () => {
    renderDashboard({ landing: false });

    expect(screen.getByRole('tab', { name: 'Dashboards' })).toHaveAttribute(
      'href',
      '/solutions/cache/instances/cache-prod-01/monitoring',
    );
    expect(screen.queryByRole('link', { name: 'Back to Monitoring' })).toBeNull();
  });

  it('omits the standalone title when landing (already inside InstanceShell chrome)', () => {
    renderDashboard({ landing: true });

    expect(screen.queryByRole('heading', { name: 'Dashboard' })).toBeNull();
  });

  /** Plan `126`'s second defect: Explore was reachable only from empty states. */
  it('offers Explore from a populated dashboard', () => {
    renderDashboard({ landing: true });

    expect(screen.getByRole('tab', { name: 'Explore' })).toHaveAttribute(
      'href',
      '/solutions/cache/instances/cache-prod-01/monitoring/explore',
    );
  });

  /**
   * Task 132 replaced the two-widget fixture (one line, one single-stat)
   * with `reference/app/dashboards.jsx`'s own six, so every mark and every
   * widget state is exercised on screen rather than merely shipped.
   */
  it('renders the loaded dashboard’s real widgets (title + layer badge), via DashboardGrid', async () => {
    renderDashboard();
    await waitFor(() => {
      expect(screen.getByText('Hit rate')).toBeInTheDocument();
    });

    // A line widget with a threshold, a single-stat, a gauge and a widget
    // whose schema this console does not know.
    expect(screen.getByText('Evictions / min')).toBeInTheDocument();
    expect(screen.getByText('Entries')).toBeInTheDocument();
    expect(screen.getByText('Hit rate (now)')).toBeInTheDocument();
    expect(screen.getByText('Miss count')).toBeInTheDocument();
    // The hidden default renders as its placeholder, not as a live widget.
    expect(screen.getByText('Hidden default · p99 GET latency')).toBeInTheDocument();

    // `LayerBadge` (task 086): three `product-default` widgets read
    // "package", three `shared` ones read "operator".
    expect(screen.getAllByText('package').length).toBeGreaterThan(0);

    await waitFor(() => {
      expect(screen.queryAllByRole('status', { name: 'Loading widget' })).toHaveLength(0);
    });
  });

  it('renders a hidden default as a restorable placeholder alongside the live widgets', async () => {
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
              hidden: true,
            },
          ],
          layout: [{ widgetId: 'w1', x: 0, y: 0, w: 8, h: 1, order: 0 }],
        }),
      ),
    );
    renderDashboard();

    await waitFor(() => {
      expect(screen.getByText('Hidden default · Hit rate')).toBeInTheDocument();
    });
    expect(screen.getByRole('button', { name: 'Restore' })).toBeInTheDocument();
    // Not the empty state — a fully-hidden dashboard still has content, just none of it live.
    expect(screen.queryByText('No widgets saved yet')).toBeNull();
  });

  it('renders the first-run entity summary, not a plain empty state, when the dashboard has zero widgets', async () => {
    server.use(
      http.get('*/console/v1/dashboards/:dashboardId', ({ params }) =>
        HttpResponse.json({
          id: String(params['dashboardId']),
          schemaVersion: 1,
          widgets: [],
          layout: [],
        }),
      ),
    );
    renderDashboard();

    await waitFor(() => {
      expect(screen.getByText('No dashboards saved yet')).toBeInTheDocument();
    });
    // Task 089's first-run view, not the plain layer-filtered `EmptyState`.
    expect(screen.queryByText('No widgets saved yet')).toBeNull();
    expect(screen.getByRole('button', { name: 'Open Explore' })).toBeInTheDocument();
  });

  it('filters the widget list by layer', async () => {
    const user = userEvent.setup();
    renderDashboard();
    await waitFor(() => {
      expect(screen.getByText('Hit rate')).toBeInTheDocument();
    });

    await user.click(screen.getByLabelText('Layer'));
    await user.click(await screen.findByRole('option', { name: 'Shared' }));

    // Task 132's fixture spans both layers, so this now filters rather than
    // emptying: the `shared` widgets stay and the `product-default` ones go.
    await waitFor(() => {
      expect(screen.queryByText('Hit rate')).toBeNull();
    });
    expect(screen.getByText('Entries')).toBeInTheDocument();
    expect(screen.getByText('Hit rate (now)')).toBeInTheDocument();
    expect(screen.queryByText('Evictions / min')).toBeNull();
  });

  /**
   * The empty state is a filter that matches nothing — distinct from a
   * dashboard with no widgets at all, which is `MonitoringFirstRun`.
   */
  it('shows the layer-filtered empty state when a filter matches no widget', async () => {
    const user = userEvent.setup();
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
          layout: [{ widgetId: 'w1', x: 0, y: 0, w: 6, h: 2, order: 0 }],
        }),
      ),
    );
    renderDashboard();
    await waitFor(() => {
      expect(screen.getByText('Hit rate')).toBeInTheDocument();
    });

    await user.click(screen.getByLabelText('Layer'));
    await user.click(await screen.findByRole('option', { name: 'Shared' }));

    await waitFor(() => {
      expect(screen.getByText('No widgets saved yet')).toBeInTheDocument();
    });
  });

  it('renders a loading skeleton before the dashboard resolves', () => {
    renderDashboard();
    expect(document.querySelectorAll('[data-skeleton]').length).toBeGreaterThan(0);
  });

  it('renders the error panel with retry on a failed load', async () => {
    server.use(
      http.get('*/console/v1/dashboards/:dashboardId', () =>
        HttpResponse.json({ code: 'INTERNAL', message: 'boom' }, { status: 500 }),
      ),
    );
    renderDashboard();
    await waitFor(() => {
      expect(screen.getByText('Dashboard failed to load')).toBeInTheDocument();
    });
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });

  it('disables refresh and states why for a custom (absolute) range', async () => {
    const user = userEvent.setup();
    renderDashboard();
    await user.click(screen.getByLabelText('Time range'));
    await user.click(await screen.findByRole('option', { name: 'Custom range' }));

    expect(screen.getByLabelText('Refresh')).toHaveAttribute('aria-disabled', 'true');
    expect(
      screen.getByText('Refresh is disabled for a fixed range — there is nothing new to fetch.'),
    ).toBeInTheDocument();
  });

  it('floors a requested refresh to the estimated served bucket for the current (24h/raw-tier) range', async () => {
    const user = userEvent.setup();
    renderDashboard();
    await user.click(screen.getByLabelText('Refresh'));
    await user.click(await screen.findByRole('option', { name: '10s' }));

    // 24h falls in the raw tier (`≤7d`) — the fixture's raw tier advertises `minBucketSeconds: 15`.
    expect(
      screen.getByText(
        'Flooring to 15s — polling faster than the tier this range would serve just re-fetches the same bucket.',
      ),
    ).toBeInTheDocument();
  });

  it('shows a paused note once the tab goes hidden while a refresh interval is set', async () => {
    const user = userEvent.setup();
    renderDashboard();
    await user.click(screen.getByLabelText('Refresh'));
    await user.click(await screen.findByRole('option', { name: '10s' }));

    act(() => {
      Object.defineProperty(document, 'hidden', { configurable: true, get: () => true });
      document.dispatchEvent(new Event('visibilitychange'));
    });

    expect(screen.getByText('Refresh paused — this tab is hidden.')).toBeInTheDocument();
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => false });
  });

  it('reflects a range change in the URL', async () => {
    const user = userEvent.setup();
    renderDashboard();
    await user.click(screen.getByLabelText('Time range'));
    await user.click(await screen.findByRole('option', { name: 'Last 7d' }));
    await waitFor(() => {
      expect(within(screen.getByLabelText('Time range')).getByText('Last 7d')).toBeInTheDocument();
    });
  });
});
