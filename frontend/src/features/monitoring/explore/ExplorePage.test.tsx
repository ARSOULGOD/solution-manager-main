import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { HttpResponse, http } from 'msw';
import { useLocation } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { createConsoleClient } from '@shared/api';
import { server } from '@shared/testdoubles/server';
import { reflowFloorPx } from '@shared/theme/breakpoints';
import { useToastStore } from '@shared/ui/Toaster';

import { setViewportWidth } from '../../../test/matchMedia';
import { assertReflow } from '../../../test/reflow';
import { renderWithProviders } from '../../../test/renderWithProviders';
import { MetricsProvider } from '../MetricsProvider';

import { loadExploreDraft, saveExploreDraft } from './exploreDraftStore';
import { ExplorePage } from './ExplorePage';

// This suite now selects real metrics/instances, so `ExploreResult`
// (task 082) can genuinely reach `ChartRenderer` and mount a real chart —
// mocked the same way `ChartRenderer.test.tsx` mocks it, since jsdom has
// no canvas 2D context and a real `echarts.init` throws trying to use one.
vi.mock('echarts', () => ({ init: vi.fn(() => ({ setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn() })) }));

const consoleClient = createConsoleClient('/api');

// A test's own draft never survives it — `ExplorePage`'s unmount effect
// (task 083) discards it, and RTL's `cleanup()` (src/test/setup.ts) runs
// that unmount after every test. This is a second line of defence for the
// tests below that seed `sessionStorage` directly, under a fixed id.
afterEach(() => {
  sessionStorage.clear();
  useToastStore.setState({ toasts: [] });
});

// Reads the live URL alongside `ExplorePage` — `MemoryRouter` has no route
// table in these tests (same as every other suite using this helper), so
// there is nothing else that would expose the current search string.
function LocationProbe({ onLocation }: { onLocation: (search: string) => void }) {
  onLocation(useLocation().search);
  return null;
}

// `ExplorePage` calls `useMetrics()`/`useCatalog` since task 078 —
// `renderWithProviders` gives every test `QueryClientProvider`, so
// `MetricsProvider` just needs to sit inside it (same nesting
// `MetricsProvider.test.tsx`/`useCatalog.test.tsx` use, minus the extra
// `QueryClientProvider` `renderWithProviders` already supplies).
function renderExplorePage(
  props: { solutionId: string; instanceId: string },
  options?: { route?: string; onLocation?: (search: string) => void },
) {
  return renderWithProviders(
    <MetricsProvider consoleClient={consoleClient}>
      <ExplorePage {...props} />
      {options?.onLocation && <LocationProbe onLocation={options.onLocation} />}
    </MetricsProvider>,
    options?.route !== undefined ? { route: options.route } : undefined,
  );
}

describe('ExplorePage', () => {
  it('renders all five numbered steps, in order', () => {
    renderExplorePage({ solutionId: 'cache', instanceId: 'cache-prod-01' });
    const titles = ['Metric', 'Instances', 'Series by', 'Aggregation', 'Group by'];
    for (const title of titles) {
      expect(screen.getByText(title)).toBeInTheDocument();
    }
  });

  it('locks steps 2-4 with a stated reason when no metric is selected (today’s true state)', () => {
    renderExplorePage({ solutionId: 'cache', instanceId: 'cache-prod-01' });
    const reasons = screen.getAllByText('Pick a metric first.');
    // Instances, Series by, Aggregation — three locked steps, one reason each.
    expect(reasons).toHaveLength(3);
  });

  it('step 4 renders the real AggregationControl, disabled while locked', () => {
    renderExplorePage({ solutionId: 'cache', instanceId: 'cache-prod-01' });
    expect(screen.getByLabelText('Across instances')).toHaveAttribute('aria-disabled', 'true');
  });

  it('locks step 5 (group by) with the aggregation-not-set reason', () => {
    renderExplorePage({ solutionId: 'cache', instanceId: 'cache-prod-01' });
    expect(screen.getByText('Turn on aggregation across instances first.')).toBeInTheDocument();
  });

  it('step 1 (metric) is never locked', () => {
    renderExplorePage({ solutionId: 'cache', instanceId: 'cache-prod-01' });
    const metricStep = document.querySelector('[data-control-step="1"]');
    expect(metricStep).toHaveAttribute('data-locked', 'false');
  });

  it('renders the empty-selection state naming the missing metric, not a fabricated chart', () => {
    renderExplorePage({ solutionId: 'cache', instanceId: 'cache-prod-01' });
    expect(screen.getByText('Select a metric')).toBeInTheDocument();
  });

  it('renders the empty-selection state naming missing instances once a metric is picked', async () => {
    const user = userEvent.setup();
    renderExplorePage({ solutionId: 'cache', instanceId: 'cache-prod-01' });
    await waitFor(() => expect(screen.getByRole('radio', { name: 'cache.hit_rate' })).not.toBeNull());

    await user.click(screen.getByRole('radio', { name: 'cache.hit_rate' }));

    expect(screen.getByText('Select at least one instance')).toBeInTheDocument();
  });

  /**
   * Task 130 replaced the one-way "← Monitoring" link with the
   * Dashboards/Explore sub-tab bar. The guarantee is the same — Explore
   * always routes back to the instance's Monitoring category — but it now
   * comes from the bar, which also routes the other way.
   */
  it('routes back to the instance’s Monitoring category via the Dashboards tab', () => {
    renderExplorePage({ solutionId: 'cache', instanceId: 'cache-prod-01' });

    expect(screen.getByRole('tab', { name: 'Dashboards' })).toHaveAttribute(
      'href',
      '/solutions/cache/instances/cache-prod-01/monitoring',
    );
    expect(screen.queryByRole('link', { name: 'Back to Monitoring' })).toBeNull();
  });

  it('step 2 renders the real InstanceSelector, disabled while locked, capped from capabilities', async () => {
    renderExplorePage({ solutionId: 'cache', instanceId: 'cache-prod-01' });
    await waitFor(() => expect(screen.getAllByRole('checkbox').length).toBeGreaterThan(0));
    for (const checkbox of screen.getAllByRole('checkbox')) {
      expect(checkbox).toBeDisabled();
    }
    // The fixture's `/capabilities` route serves `maxSeriesPerWidget: 10` —
    // asserting the same value here would pass just as well with a
    // hardcoded `10` in `ExplorePage`, so this only proves the number is
    // NOT some other literal (e.g. the old prototype's 20, Conflict C6).
    await waitFor(() =>
      expect(screen.getByTestId('instance-selector-projection').textContent).toContain('/ 10'),
    );
  });

  it('step 3 renders the seriesBy toggle, disabled while locked', () => {
    renderExplorePage({ solutionId: 'cache', instanceId: 'cache-prod-01' });
    expect(screen.getByRole('button', { name: 'Instance' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Replica' })).toBeDisabled();
  });

  it('step 1 renders the real MetricSelector, catalog-driven', async () => {
    renderExplorePage({ solutionId: 'cache', instanceId: 'cache-prod-01' });
    await waitFor(() => expect(screen.getByRole('radio', { name: 'cache.hit_rate' })).not.toBeNull());
    expect(screen.getByRole('radio', { name: 'cache.custom_lag' })).toBeDisabled();
  });

  it('selecting a plottable metric unlocks steps 2-4', async () => {
    const user = userEvent.setup();
    renderExplorePage({ solutionId: 'cache', instanceId: 'cache-prod-01' });
    await waitFor(() => expect(screen.getByRole('radio', { name: 'cache.hit_rate' })).not.toBeNull());

    await user.click(screen.getByRole('radio', { name: 'cache.hit_rate' }));

    expect(screen.queryByText('Pick a metric first.')).toBeNull();
    expect(screen.getByRole('button', { name: 'Instance' })).not.toBeDisabled();
    expect(screen.getByRole('button', { name: 'Replica' })).not.toBeDisabled();
    for (const checkbox of screen.getAllByRole('checkbox')) {
      expect(checkbox).not.toBeDisabled();
    }
    expect(screen.getByLabelText('Across instances')).not.toHaveAttribute('aria-disabled', 'true');
    // Step 5 (group by) stays locked — aggregation still isn't set.
    expect(screen.getByText('Turn on aggregation across instances first.')).not.toBeNull();
  });

  it('selecting cache.hit_rate then setting aggregation shows its region group-by toggle', async () => {
    const user = userEvent.setup();
    renderExplorePage({ solutionId: 'cache', instanceId: 'cache-prod-01' });
    await waitFor(() => expect(screen.getByRole('radio', { name: 'cache.hit_rate' })).not.toBeNull());

    await user.click(screen.getByRole('radio', { name: 'cache.hit_rate' }));
    await user.click(screen.getByLabelText('Across instances'));
    await user.click(await screen.findByRole('option', { name: 'Across instances: sum' }));

    expect(screen.queryByText('Turn on aggregation across instances first.')).toBeNull();
    expect(screen.getByRole('button', { name: 'region' })).not.toBeDisabled();
  });

  it('selecting cache.hit_count (no label keys) then setting aggregation shows the no-labels caption', async () => {
    const user = userEvent.setup();
    renderExplorePage({ solutionId: 'cache', instanceId: 'cache-prod-01' });
    await waitFor(() => expect(screen.getByRole('radio', { name: 'cache.hit_count' })).not.toBeNull());

    await user.click(screen.getByRole('radio', { name: 'cache.hit_count' }));
    await user.click(screen.getByLabelText('Across instances'));
    await user.click(await screen.findByRole('option', { name: 'Across instances: sum' }));

    expect(screen.getByText('This metric has no label keys to group by.')).not.toBeNull();
  });

  it('clicking "Aggregate into one line" at the cap really sets aggregation and unlocks group by', async () => {
    const user = userEvent.setup();
    renderExplorePage({ solutionId: 'cache', instanceId: 'cache-prod-01' });
    await waitFor(() => expect(screen.getByRole('radio', { name: 'cache.hit_rate' })).not.toBeNull());
    await user.click(screen.getByRole('radio', { name: 'cache.hit_rate' }));

    await waitFor(() => expect(screen.getAllByRole('checkbox').length).toBeGreaterThan(10));
    const checkboxes = screen.getAllByRole('checkbox');
    // maxSeriesPerWidget is 10 (fixture) — selecting 10 of 12 instances (seriesBy: instance)
    // reaches the cap and leaves the remaining two blocked, surfacing the escape-route panel.
    for (const checkbox of checkboxes.slice(0, 10)) {
      await user.click(checkbox);
    }
    await user.click(screen.getByRole('button', { name: 'Aggregate into one line' }));

    expect(screen.getByLabelText('Across instances')).toHaveTextContent('Across instances: average');
    expect(screen.queryByText('Turn on aggregation across instances first.')).toBeNull();
  });

  it('switching the selected metric resets aggregation and group by, re-locking step 5', async () => {
    const user = userEvent.setup();
    renderExplorePage({ solutionId: 'cache', instanceId: 'cache-prod-01' });
    await waitFor(() => expect(screen.getByRole('radio', { name: 'cache.hit_rate' })).not.toBeNull());

    await user.click(screen.getByRole('radio', { name: 'cache.hit_rate' }));
    await user.click(screen.getByLabelText('Across instances'));
    await user.click(await screen.findByRole('option', { name: 'Across instances: sum' }));
    expect(screen.queryByText('Turn on aggregation across instances first.')).toBeNull();

    await user.click(screen.getByRole('radio', { name: 'cache.evictions' }));

    expect(screen.getByText('Turn on aggregation across instances first.')).not.toBeNull();
    expect(screen.getByLabelText('Across instances')).toHaveTextContent('Across instances: none (one line each)');
  });

  it('selecting a type-suspect metric also unlocks steps 2-4 — it is selectable, only needs-typing is not', async () => {
    const user = userEvent.setup();
    renderExplorePage({ solutionId: 'cache', instanceId: 'cache-prod-01' });
    await waitFor(() => expect(screen.getByRole('radio', { name: 'cache.queue_depth' })).not.toBeNull());

    await user.click(screen.getByRole('radio', { name: 'cache.queue_depth' }));

    expect(screen.queryByText('Pick a metric first.')).toBeNull();
    expect(screen.getByRole('button', { name: 'Instance' })).not.toBeDisabled();
  });

  it('the result column renders TimeRangePicker defaulted to 24h and ResolutionControl, neither gated behind metric selection', () => {
    renderExplorePage({ solutionId: 'cache', instanceId: 'cache-prod-01' });
    expect(screen.getByLabelText('Time range')).toHaveTextContent('Last 24h');
    expect(screen.getByRole('button', { name: 'Auto' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('Raw becomes available once capabilities resolve (fixture advertises features.forceRaw)', async () => {
    renderExplorePage({ solutionId: 'cache', instanceId: 'cache-prod-01' });
    await waitFor(() => expect(screen.getByRole('button', { name: 'Raw' })).not.toBeNull());
  });

  it('a real query renders a real chart once a metric and an instance are both selected', async () => {
    const user = userEvent.setup();
    renderExplorePage({ solutionId: 'cache', instanceId: 'cache-prod-01' });
    await waitFor(() => expect(screen.getByRole('radio', { name: 'cache.hit_rate' })).not.toBeNull());

    await user.click(screen.getByRole('radio', { name: 'cache.hit_rate' }));
    await waitFor(() => expect(screen.getAllByRole('checkbox').length).toBeGreaterThan(0));
    await user.click(screen.getAllByRole('checkbox')[0]!);

    await waitFor(() =>
      expect(screen.getByLabelText('Chart: cache.hit_rate')).toBeInTheDocument(),
    );
  });

  it('a genuinely reachable 422 (aggregating one instance) renders the typed error, not a crash', async () => {
    const user = userEvent.setup();
    renderExplorePage({ solutionId: 'cache', instanceId: 'cache-prod-01' });
    await waitFor(() => expect(screen.getByRole('radio', { name: 'cache.hit_rate' })).not.toBeNull());

    await user.click(screen.getByRole('radio', { name: 'cache.hit_rate' }));
    await waitFor(() => expect(screen.getAllByRole('checkbox').length).toBeGreaterThan(0));
    await user.click(screen.getAllByRole('checkbox')[0]!);
    await user.click(screen.getByLabelText('Across instances'));
    await user.click(await screen.findByRole('option', { name: 'Across instances: sum' }));

    expect(await screen.findByText('Request refused — not retried')).toBeInTheDocument();
    expect(
      screen.getByText('Aggregation across instances needs at least two instances to combine.'),
    ).toBeInTheDocument();
    expect(screen.getByText('Error code: AGGREGATION_NOT_APPLICABLE')).toBeInTheDocument();
  });
});

describe('ExplorePage draft persistence (task 083, Dec `FE-D21` `DM-D9`)', () => {
  it('states the sharing limitation up front, not implied away', () => {
    renderExplorePage({ solutionId: 'cache', instanceId: 'cache-prod-01' });
    expect(
      screen.getByText(
        'This link restores your time range and instances in this browser tab, but not the exploration itself. Save as a widget to share this view with someone else.',
      ),
    ).toBeInTheDocument();
  });

  it('mints a `?draft=` id into the URL on mount', async () => {
    let search = '';
    renderExplorePage(
      { solutionId: 'cache', instanceId: 'cache-prod-01' },
      {
        onLocation: (s) => {
          search = s;
        },
      },
    );
    await waitFor(() => expect(search).toMatch(/[?&]draft=[0-9a-f]{12}/));
  });

  it('puts instance selection in the URL, never the metric/aggregation document', async () => {
    const user = userEvent.setup();
    let search = '';
    renderExplorePage(
      { solutionId: 'cache', instanceId: 'cache-prod-01' },
      {
        onLocation: (s) => {
          search = s;
        },
      },
    );
    await waitFor(() => expect(screen.getByRole('radio', { name: 'cache.hit_rate' })).not.toBeNull());

    await user.click(screen.getByRole('radio', { name: 'cache.hit_rate' }));
    await waitFor(() => expect(screen.getAllByRole('checkbox').length).toBeGreaterThan(0));
    await user.click(screen.getAllByRole('checkbox')[0]!);
    await user.click(screen.getByLabelText('Across instances'));
    await user.click(await screen.findByRole('option', { name: 'Across instances: sum' }));

    await waitFor(() => expect(search).toContain('sel='));
    // No widget JSON in the URL (acceptance) — the metric/aggregation/group-by
    // document lives only in sessionStorage, keyed by `draft`.
    expect(search).not.toContain('metric=');
    expect(search).not.toContain('acrossInstances=');
    expect(search).not.toContain('groupBy=');
  });

  it('restores metric and aggregation from sessionStorage when the URL already carries the draft id', async () => {
    saveExploreDraft('restore-test', {
      metric: 'cache.hit_rate',
      seriesBy: 'instance',
      acrossInstances: 'avg',
      groupBy: [],
      resolution: 'auto',
    });

    renderExplorePage(
      { solutionId: 'cache', instanceId: 'cache-prod-01' },
      { route: '/?draft=restore-test' },
    );

    await waitFor(() => expect(screen.getByRole('radio', { name: 'cache.hit_rate' })).toBeChecked());
    expect(screen.getByLabelText('Across instances')).toHaveTextContent('Across instances: average');
  });

  it('discards the sessionStorage draft on unmount, since nothing here can save it yet', async () => {
    const user = userEvent.setup();
    let search = '';
    const { unmount } = renderExplorePage(
      { solutionId: 'cache', instanceId: 'cache-prod-01' },
      {
        onLocation: (s) => {
          search = s;
        },
      },
    );
    await waitFor(() => expect(screen.getByRole('radio', { name: 'cache.hit_rate' })).not.toBeNull());
    await user.click(screen.getByRole('radio', { name: 'cache.hit_rate' }));
    await waitFor(() => expect(search).toMatch(/draft=[0-9a-f]{12}/));
    const draftId = /draft=([0-9a-f]{12})/.exec(search)?.[1] as string;
    expect(loadExploreDraft(draftId)?.metric).toBe('cache.hit_rate');

    unmount();

    expect(loadExploreDraft(draftId)).toBeNull();
  });
});

describe('ExplorePage · Save as widget (task 084, Dec `DM-D6` `DM-D12` `DM-D21` `SM-D31`)', () => {
  it('Save as widget is disabled with a stated reason before a metric and an instance are both chosen', () => {
    renderExplorePage({ solutionId: 'cache', instanceId: 'cache-prod-01' });
    expect(screen.getByRole('button', { name: 'Save as widget' })).toBeDisabled();
  });

  it('Save as widget opens SaveWidgetDialog once a metric and an instance are chosen', async () => {
    const user = userEvent.setup();
    renderExplorePage({ solutionId: 'cache', instanceId: 'cache-prod-01' });
    await waitFor(() => expect(screen.getByRole('radio', { name: 'cache.hit_rate' })).not.toBeNull());
    await user.click(screen.getByRole('radio', { name: 'cache.hit_rate' }));
    await waitFor(() => expect(screen.getAllByRole('checkbox').length).toBeGreaterThan(0));
    await user.click(screen.getAllByRole('checkbox')[0]!);

    await user.click(screen.getByRole('button', { name: 'Save as widget' }));

    const dialog = within(screen.getByRole('dialog'));
    expect(dialog.getByRole('heading', { name: 'Save as widget' })).toBeInTheDocument();
    expect(dialog.getByText('cache.hit_rate')).toBeInTheDocument();
  });

  it('saving with a title shows a success toast naming it, and closes the dialog', async () => {
    const user = userEvent.setup();
    renderExplorePage({ solutionId: 'cache', instanceId: 'cache-prod-01' });
    await waitFor(() => expect(screen.getByRole('radio', { name: 'cache.hit_rate' })).not.toBeNull());
    await user.click(screen.getByRole('radio', { name: 'cache.hit_rate' }));
    await waitFor(() => expect(screen.getAllByRole('checkbox').length).toBeGreaterThan(0));
    await user.click(screen.getAllByRole('checkbox')[0]!);
    await user.click(screen.getByRole('button', { name: 'Save as widget' }));

    await user.type(screen.getByLabelText(/^Widget title/), 'Hit rate — production');
    await user.click(screen.getByRole('button', { name: 'Save widget' }));

    // `<Toaster>` is not mounted by `renderWithProviders` (it's an
    // app-shell concern, `Toaster.test.tsx`'s own territory) — asserting
    // on the store directly proves the same thing without duplicating
    // that component's own rendering tests.
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(useToastStore.getState().toasts).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          title: 'Widget saved',
          detail: '"Hit rate — production" was added to this instance’s dashboard.',
        }),
      ]),
    );
  });

  /**
   * Task 132 — the outcome of the plan's own walk-through: Monitoring →
   * Explore → pick a metric → pick instances → Save as widget → SEE it on
   * the dashboard. The toast alone left the operator on Explore with no
   * way to tell whether anything had been added, and the fixture happily
   * reported success while discarding the widget.
   */
  it('navigates to the dashboard on a successful save, so the widget is visible', async () => {
    const user = userEvent.setup();
    renderExplorePage({ solutionId: 'cache', instanceId: 'cache-prod-01' });
    await waitFor(() => expect(screen.getByRole('radio', { name: 'cache.hit_rate' })).not.toBeNull());
    await user.click(screen.getByRole('radio', { name: 'cache.hit_rate' }));
    await waitFor(() => expect(screen.getAllByRole('checkbox').length).toBeGreaterThan(0));
    await user.click(screen.getAllByRole('checkbox')[0]!);
    await user.click(screen.getByRole('button', { name: 'Save as widget' }));

    await user.type(screen.getByLabelText(/^Widget title/), 'Hit rate — production');
    await user.click(screen.getByRole('button', { name: 'Save widget' }));

    // The tab bar reads the router location, so the Dashboards tab going
    // active IS the navigation (`MemoryRouter` has no `window.location`).
    await waitFor(() => {
      expect(screen.getByRole('tab', { name: 'Dashboards' }).getAttribute('aria-selected')).toBe(
        'true',
      );
    });
  });
  it('a saved draft is NOT discarded on navigate-away (`DM-D9`\'s "unless saved")', async () => {
    const user = userEvent.setup();
    let search = '';
    const { unmount } = renderExplorePage(
      { solutionId: 'cache', instanceId: 'cache-prod-01' },
      {
        onLocation: (s) => {
          search = s;
        },
      },
    );
    await waitFor(() => expect(screen.getByRole('radio', { name: 'cache.hit_rate' })).not.toBeNull());
    await user.click(screen.getByRole('radio', { name: 'cache.hit_rate' }));
    await waitFor(() => expect(screen.getAllByRole('checkbox').length).toBeGreaterThan(0));
    await user.click(screen.getAllByRole('checkbox')[0]!);
    await waitFor(() => expect(search).toMatch(/draft=[0-9a-f]{12}/));
    const draftId = /draft=([0-9a-f]{12})/.exec(search)?.[1] as string;

    await user.click(screen.getByRole('button', { name: 'Save as widget' }));
    await user.type(screen.getByLabelText(/^Widget title/), 'Hit rate — production');
    await user.click(screen.getByRole('button', { name: 'Save widget' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());

    unmount();

    expect(loadExploreDraft(draftId)).not.toBeNull();
  });

  it('a failed save renders the error in place, without discarding the draft', async () => {
    server.use(
      http.post(
        '*/console/v1/dashboards/:dashboardId/widgets',
        () => HttpResponse.json({ message: 'The dashboard could not be reached.' }, { status: 500 }),
      ),
    );
    const user = userEvent.setup();
    renderExplorePage({ solutionId: 'cache', instanceId: 'cache-prod-01' });
    await waitFor(() => expect(screen.getByRole('radio', { name: 'cache.hit_rate' })).not.toBeNull());
    await user.click(screen.getByRole('radio', { name: 'cache.hit_rate' }));
    await waitFor(() => expect(screen.getAllByRole('checkbox').length).toBeGreaterThan(0));
    await user.click(screen.getAllByRole('checkbox')[0]!);
    await user.click(screen.getByRole('button', { name: 'Save as widget' }));
    await user.type(screen.getByLabelText(/^Widget title/), 'Hit rate — production');

    await user.click(screen.getByRole('button', { name: 'Save widget' }));

    expect(await screen.findByText('The dashboard could not be reached.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save widget' })).toBeInTheDocument();
  });
});

describe('ExplorePage responsive controls at the 320px reflow floor (task 109, FE-D29)', () => {
  it('all five steps are reachable disclosure buttons, expanded by default, with locked reasons intact', () => {
    setViewportWidth(reflowFloorPx);
    renderExplorePage({ solutionId: 'cache', instanceId: 'cache-prod-01' });

    const titles = ['Metric', 'Instances', 'Series by', 'Aggregation', 'Group by'];
    for (const title of titles) {
      const toggle = screen.getByRole('button', { name: new RegExp(title) });
      expect(toggle).toHaveAttribute('aria-expanded', 'true');
    }
    // Same lock reasons as the desktop case (first test in this file) —
    // becoming an accordion below `lg` never hides why a step is locked.
    expect(screen.getAllByText('Pick a metric first.')).toHaveLength(3);
  });

  it('collapsing a step keeps its own reason visible and does not affect the other steps', async () => {
    setViewportWidth(reflowFloorPx);
    const user = userEvent.setup();
    renderExplorePage({ solutionId: 'cache', instanceId: 'cache-prod-01' });

    const metricToggle = screen.getByRole('button', { name: /Metric/ });
    await user.click(metricToggle);

    expect(metricToggle).toHaveAttribute('aria-expanded', 'false');
    // The other four steps are untouched.
    expect(screen.getByRole('button', { name: /Instances/ })).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getAllByText('Pick a metric first.')).toHaveLength(3);
  });

  it('no page-body horizontal scroll at the reflow floor', () => {
    setViewportWidth(reflowFloorPx);
    const { container } = renderExplorePage({ solutionId: 'cache', instanceId: 'cache-prod-01' });
    const root = container.firstElementChild;
    expect(root).toBeInstanceOf(HTMLElement);
    if (!(root instanceof HTMLElement)) {
      return;
    }
    expect(() => {
      assertReflow(root, reflowFloorPx);
    }).not.toThrow();
  });
});
