import { QueryClient } from '@tanstack/react-query';
import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { HttpResponse, http } from 'msw';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { createConsoleClient } from '@shared/api';
import { server } from '@shared/testdoubles/server';

import { renderWithProviders } from '../../../test/renderWithProviders';
import type { WidgetConfig, WidgetPlacement } from '../dashboardRepository';
import { MetricsProvider } from '../MetricsProvider';

import { WidgetFrame, widgetBodyHeight } from './WidgetFrame';

// This suite exercises `WidgetFrame`'s OWN contract (visibility gating,
// query lifecycle, dim-not-blank, schema versioning, error isolation) —
// not `ChartRenderer`'s internals, which have their own test files. Fully
// mocking it (rather than `echarts` alone, as `ExplorePage.test.tsx` does)
// keeps this suite from needing jsdom canvas machinery at all, and lets
// one test make it throw on demand without touching real chart code.
let chartShouldThrow = false;
vi.mock('../chart/ChartRenderer', () => ({
  // Keyed on `ariaLabel` (built from the widget's own title) rather than a
  // single global flag, so a "siblings survive" test can render two real
  // `WidgetFrame`s side by side and make only ONE of them throw.
  ChartRenderer: ({ ariaLabel }: { ariaLabel?: string }) => {
    if (chartShouldThrow && ariaLabel?.includes('Boom') === true) {
      throw new Error('chart exploded');
    }
    return <div data-testid="real-chart">{ariaLabel}</div>;
  },
}));

const consoleClient = createConsoleClient('/api');

function makeWidget(overrides: Partial<WidgetConfig> = {}): WidgetConfig {
  return {
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
    ...overrides,
  };
}

const PLACEMENT: WidgetPlacement = { widgetId: 'w1', x: 0, y: 0, w: 6, h: 2, order: 0 };
const ABSOLUTE_RANGE = {
  from: new Date(Date.now() - 86_400_000).toISOString(),
  to: new Date().toISOString(),
};

function renderWidget(
  overrides: Partial<Parameters<typeof WidgetFrame>[0]> = {},
  queryClient?: QueryClient,
) {
  return renderWithProviders(
    <MetricsProvider consoleClient={consoleClient}>
      <WidgetFrame
        widget={makeWidget()}
        placement={PLACEMENT}
        absoluteRange={ABSOLUTE_RANGE}
        resolution="auto"
        refreshSeconds={0}
        editing={false}
        onMoveEarlier={() => {}}
        onMoveLater={() => {}}
        canMoveEarlier={false}
        canMoveLater={false}
        onHide={() => {}}
        hidePending={false}
        {...overrides}
      />
    </MetricsProvider>,
    queryClient !== undefined ? { queryClient } : undefined,
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
  chartShouldThrow = false;
});

class NeverIntersectingObserver implements IntersectionObserver {
  readonly root: Element | Document | null = null;
  readonly rootMargin: string = '';
  readonly scrollMargin: string = '';
  readonly thresholds: readonly number[] = [];
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
  takeRecords(): IntersectionObserverEntry[] {
    return [];
  }
}

describe('WidgetFrame', () => {
  it('renders the widget title and its layer badge', () => {
    renderWidget();
    expect(screen.getByText('Hit rate')).toBeInTheDocument();
    expect(screen.getByText('package')).toBeInTheDocument();
  });

  it('renders the (mocked) chart for mark: line once the query resolves', async () => {
    renderWidget();
    await waitFor(() => {
      expect(screen.getByTestId('real-chart')).toBeInTheDocument();
    });
  });

  it('renders GaugeMark for mark: gauge', async () => {
    renderWidget({
      widget: makeWidget({
        mark: 'gauge',
        query: {
          metric: 'cache.hit_rate',
          // `DM-D22`: gauge/single-stat need aggregation != 'none', which
          // the fixture's own `aggregationError` refuses below 2 instances.
          selector: { entityType: 'cache-engine', instanceIds: ['cache-prod-01', 'cache-prod-02'] },
          seriesBy: 'instance',
          aggregation: { acrossReplicas: 'auto', acrossInstances: 'avg' },
        },
      }),
    });
    await waitFor(() => {
      expect(screen.queryByRole('status', { name: 'Loading widget' })).toBeNull();
    });
    expect(screen.queryByText('No data in range')).toBeNull();
    // GaugeMark draws an SVG arc — no chart, no ChartRenderer mock involved.
    expect(document.querySelector('svg')).not.toBeNull();
  });

  it('renders SingleStatMark for mark: single-stat', async () => {
    renderWidget({
      widget: makeWidget({
        id: 'w2',
        mark: 'single-stat',
        query: {
          metric: 'cache.size',
          selector: { entityType: 'cache-engine', instanceIds: [] },
          seriesBy: 'instance',
          aggregation: { acrossReplicas: 'auto', acrossInstances: 'none' },
        },
      }),
      placement: { ...PLACEMENT, widgetId: 'w2' },
    });
    await waitFor(() => {
      expect(screen.queryByRole('status', { name: 'Loading widget' })).toBeNull();
    });
    expect(screen.queryByText('No data in range')).toBeNull();
    expect(screen.queryByTestId('real-chart')).toBeNull();
  });

  it('issues no request for an off-screen widget', async () => {
    vi.stubGlobal('IntersectionObserver', NeverIntersectingObserver);
    let requestCount = 0;
    server.use(
      http.post('*/console/v1/query/range', () => {
        requestCount += 1;
        return HttpResponse.json({}, { status: 500 });
      }),
    );

    renderWidget();
    expect(screen.getByRole('status', { name: 'Loading widget' })).toBeInTheDocument();

    // Give any (wrongly) in-flight request a chance to land.
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(requestCount).toBe(0);
    expect(screen.getByRole('status', { name: 'Loading widget' })).toBeInTheDocument();
  });

  it('renders a schemaVersion+1 document read-only and whole, issuing no request', async () => {
    let requestCount = 0;
    server.use(
      http.post('*/console/v1/query/range', () => {
        requestCount += 1;
        return HttpResponse.json({}, { status: 500 });
      }),
    );

    renderWidget({ widget: makeWidget({ schemaVersion: 2 as 1 }) });

    expect(screen.getByText('Read-only')).toBeInTheDocument();
    expect(screen.getByText(/v2/)).toBeInTheDocument();
    expect(screen.getByText(/v1/)).toBeInTheDocument();
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(requestCount).toBe(0);
  });

  it('renders the ErrorPanel with retry on a failed query (not the render-crash boundary)', async () => {
    server.use(
      http.post('*/console/v1/query/range', () =>
        HttpResponse.json({ code: 'INTERNAL', message: 'boom' }, { status: 500 }),
      ),
    );
    renderWidget();
    await waitFor(() => {
      expect(screen.getByText('This widget failed to load')).toBeInTheDocument();
    });
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });

  it('isolates a render throw behind its own error boundary, distinct from a query error', async () => {
    chartShouldThrow = true;
    renderWidget({ widget: makeWidget({ title: 'Boom widget' }) });
    await waitFor(() => {
      expect(screen.getByText('This widget failed to render')).toBeInTheDocument();
    });
  });

  it('a throwing widget leaves a sibling widget mounted and refreshing', async () => {
    chartShouldThrow = true;
    const boomWidget = makeWidget({ id: 'boom', title: 'Boom widget' });
    const healthyWidget = makeWidget({ id: 'healthy', title: 'Healthy widget' });

    renderWithProviders(
      <MetricsProvider consoleClient={consoleClient}>
        <div>
          <WidgetFrame
            widget={boomWidget}
            placement={{ ...PLACEMENT, widgetId: 'boom' }}
            absoluteRange={ABSOLUTE_RANGE}
            resolution="auto"
            refreshSeconds={0}
            editing={false}
            onMoveEarlier={() => {}}
            onMoveLater={() => {}}
            canMoveEarlier={false}
            canMoveLater={false}
            onHide={() => {}}
            hidePending={false}
          />
          <WidgetFrame
            widget={healthyWidget}
            placement={{ ...PLACEMENT, widgetId: 'healthy' }}
            absoluteRange={ABSOLUTE_RANGE}
            resolution="auto"
            refreshSeconds={0}
            editing={false}
            onMoveEarlier={() => {}}
            onMoveLater={() => {}}
            canMoveEarlier={false}
            canMoveLater={false}
            onHide={() => {}}
            hidePending={false}
          />
        </div>
      </MetricsProvider>,
    );

    await waitFor(() => {
      expect(screen.getByText('This widget failed to render')).toBeInTheDocument();
    });
    // The sibling never threw — it keeps rendering and refetching normally.
    await waitFor(() => {
      expect(screen.getByTestId('real-chart')).toBeInTheDocument();
    });
    expect(screen.getByText('Healthy widget')).toBeInTheDocument();
  });

  it('dims, never blanks, while a same-key refresh is in flight', async () => {
    const FIXED_RESPONSE = {
      queryVersion: 1,
      metric: { name: 'cache.hit_rate', metricType: 'gauge', unit: 'ratio' },
      render: { transform: 'none' },
      resolution: { requested: 'auto', served: 'hourly', bucketSeconds: 3600 },
      coverage: {
        requestedFrom: ABSOLUTE_RANGE.from,
        servedFrom: ABSOLUTE_RANGE.from,
        truncatedByRetention: false,
      },
      gapThresholdSeconds: 7200,
      series: [
        {
          seriesRef: { instanceId: 'cache-prod-01', placementId: null, sourceKey: null },
          label: 'cache-prod-01',
          replicaComposition: { total: 1, inactive: 0 },
          state: 'ok',
          points: [[ABSOLUTE_RANGE.to, 0.9, 60, 60]],
          gaps: [],
        },
      ],
      partial: { degraded: false, unavailable: [] },
    };

    let callCount = 0;
    // A plain `let ... | null` reassigned only inside the closure below
    // narrows to `null` at every later read (TS can't see the async
    // reassignment) — a holder object sidesteps that.
    const resolveSecondCall: { current: (() => void) | null } = { current: null };
    server.use(
      http.post('*/console/v1/query/range', async () => {
        callCount += 1;
        if (callCount === 2) {
          await new Promise<void>((resolve) => {
            resolveSecondCall.current = resolve;
          });
        }
        return HttpResponse.json(FIXED_RESPONSE);
      }),
    );

    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    renderWidget({}, queryClient);

    await waitFor(() => {
      expect(screen.getByTestId('real-chart')).toBeInTheDocument();
    });
    expect(screen.queryByRole('status', { name: 'Refreshing widget' })).toBeNull();

    act(() => {
      void queryClient.refetchQueries();
    });

    // Mid-flight: the SAME query key is refetching (`isFetching`), but
    // `data` from the first call is still populated — the chart stays
    // mounted (`refreshing` dims via `sx`, this is the a11y-visible proxy
    // for that same boolean) rather than the tree unmounting to a skeleton.
    await waitFor(() => {
      expect(screen.getByRole('status', { name: 'Refreshing widget' })).toBeInTheDocument();
    });
    expect(screen.getByTestId('real-chart')).toBeInTheDocument();

    resolveSecondCall.current?.();
    await waitFor(() => {
      expect(screen.queryByRole('status', { name: 'Refreshing widget' })).toBeNull();
    });
    expect(screen.getByTestId('real-chart')).toBeInTheDocument();
  });

  it('offers "Hide (keep default)" in the menu for a product-default widget, and calls onHide', async () => {
    const user = userEvent.setup();
    const onHide = vi.fn();
    renderWidget({ onHide });

    await user.click(screen.getByRole('button', { name: 'Widget menu' }));
    const hideItem = await screen.findByRole('menuitem', { name: 'Hide (keep default)' });
    await user.click(hideItem);

    expect(onHide).toHaveBeenCalledTimes(1);
  });

  it('disables "Hide (keep default)" while a hide/restore is already in flight', async () => {
    const user = userEvent.setup();
    renderWidget({ hidePending: true });

    await user.click(screen.getByRole('button', { name: 'Widget menu' }));
    expect(await screen.findByRole('menuitem', { name: 'Hide (keep default)' })).toHaveAttribute(
      'aria-disabled',
      'true',
    );
  });

  it('never offers "Hide (keep default)" for a shared (operator) widget', async () => {
    const user = userEvent.setup();
    renderWidget({ widget: makeWidget({ layer: 'shared' }) });

    await user.click(screen.getByRole('button', { name: 'Widget menu' }));
    expect(screen.queryByRole('menuitem', { name: 'Hide (keep default)' })).toBeNull();
  });

  it('offers "Export CSV" when streamingExport is advertised, confirms the high-consequence dialog, and streams a same-origin download', async () => {
    const user = userEvent.setup();
    renderWidget();

    // Wait for the real fetch cycle (capabilities + query) to settle before
    // opening the menu, same as every other gated-content test in this file.
    await waitFor(() => {
      expect(screen.getByTestId('real-chart')).toBeInTheDocument();
    });

    await user.click(screen.getByRole('button', { name: 'Widget menu' }));
    const exportItem = await screen.findByRole('menuitem', { name: 'Export CSV' });
    await user.click(exportItem);

    expect(await screen.findByText('Export widget as CSV?')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Start export' }));

    await waitFor(() => {
      const iframe = document.querySelector('iframe');
      expect(iframe).not.toBeNull();
      expect(iframe?.src).toContain('/export/range?');
      expect(iframe?.src).toContain('metric=cache.hit_rate');
    });
    await waitFor(() => {
      expect(screen.queryByText('Export widget as CSV?')).toBeNull();
    });
  });

  it('omits "Export CSV" entirely when the deployment does not advertise streamingExport', async () => {
    const user = userEvent.setup();
    server.use(
      http.get('*/console/v1/capabilities', () =>
        HttpResponse.json({
          queryVersion: 1,
          tiers: [{ name: 'raw', retention: 'P6M', minBucketSeconds: 15 }],
          maxRange: 'P2Y',
          maxSeriesPerWidget: 10,
          maxPointsPerSeries: 1000,
          marks: ['line', 'gauge', 'single-stat'],
          aggregations: ['sum', 'avg', 'min', 'max'],
          features: { forceRaw: true, streamingExport: false, pngExport: false },
        }),
      ),
    );
    renderWidget();

    await waitFor(() => {
      expect(screen.getByTestId('real-chart')).toBeInTheDocument();
    });
    await user.click(screen.getByRole('button', { name: 'Widget menu' }));
    expect(screen.queryByRole('menuitem', { name: 'Export CSV' })).toBeNull();
  });

  it('omits "Export CSV" for a schemaVersion+1 (too-new) document even when streamingExport is advertised', async () => {
    const user = userEvent.setup();
    renderWidget({ widget: makeWidget({ schemaVersion: 2 as 1 }) });

    expect(screen.getByText('Read-only')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Widget menu' }));
    expect(screen.queryByRole('menuitem', { name: 'Export CSV' })).toBeNull();
  });

  /**
   * Task 129 — `placement.h` finally means a height. `DashboardGrid` spans
   * `h` rows of a 150px track 14px apart, so the body a card asks for has
   * to be derived from those same two numbers or the card and its cell
   * disagree (and `Paper`'s `fill` carries `overflow: hidden`, so the
   * disagreement clips the plot rather than showing).
   */
  describe('widgetBodyHeight', () => {
    it('is one grid row for a single-row widget', () => {
      expect(widgetBodyHeight(1)).toBe(150);
    });

    it('recovers the gap a spanned cell absorbs between rows', () => {
      expect(widgetBodyHeight(2)).toBe(150 * 2 + 14);
      expect(widgetBodyHeight(3)).toBe(150 * 3 + 14 * 2);
    });

    it('grows strictly with h — a taller widget is never shorter', () => {
      const heights = [1, 2, 3, 4].map(widgetBodyHeight);
      for (let i = 1; i < heights.length; i += 1) {
        expect(heights[i]).toBeGreaterThan(heights[i - 1] ?? 0);
      }
    });

    it('floors a nonsensical h at one row rather than collapsing', () => {
      expect(widgetBodyHeight(0)).toBe(150);
      expect(widgetBodyHeight(-3)).toBe(150);
    });
  });
});
