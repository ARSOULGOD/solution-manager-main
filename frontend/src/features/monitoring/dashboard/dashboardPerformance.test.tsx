/**
 * FE-103 · the two dashboard-level performance guarantees that only show up
 * when several widgets are mounted together (design §17, §22,
 * `dynamic-metrics` §13).
 *
 * The per-widget half is already covered elsewhere and deliberately not
 * repeated here:
 *
 *   · *"off-screen widgets issue no request"* — `WidgetFrame.test.tsx`
 *     ("issues no request for an off-screen widget"), which drives a
 *     `NeverIntersectingObserver` and counts requests.
 *   · *"refresh is floored at the served bucket"* — `refreshPolicy.test.ts`,
 *     against `flooredRefreshSeconds`/`estimatedBucketSeconds` directly.
 *   · *"the legend virtualises rather than truncating"* —
 *     `ChartRenderer.test.tsx` ("never truncates membership", "bounds height
 *     by legendMaxHeightRows without reducing membership").
 *   · *"free-text inputs are debounced and enum inputs are not"* —
 *     `FilterBar.test.tsx` ("debounces free text and applies enum
 *     immediately").
 *
 * What is left is what one widget cannot show: that **eight widgets over
 * four metrics issue four requests**, and that a scheduled refresh **stops
 * while the tab is hidden**. Both are properties of the shared query cache,
 * so both need a real dashboard.
 */
import { QueryClient } from '@tanstack/react-query';
import { act } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { createConsoleClient } from '@shared/api';
import { server } from '@shared/testdoubles/server';

import { renderWithProviders, waitFor } from '../../../test/renderWithProviders';
import type { WidgetConfig, WidgetPlacement } from '../dashboardRepository';
import { MetricsProvider } from '../MetricsProvider';

import { DashboardGrid } from './DashboardGrid';

// The chart itself is not under test here — request counts are. Mocking it
// keeps the suite off jsdom's missing canvas entirely, the same reason
// `WidgetFrame.test.tsx` mocks it.
vi.mock('../chart/ChartRenderer', () => ({
  ChartRenderer: ({ ariaLabel }: { ariaLabel?: string }) => <div>{ariaLabel}</div>,
}));

const consoleClient = createConsoleClient('/api');

/** An absolute range, so every widget resolves to the identical request window. */
const ABSOLUTE_RANGE = { from: '2026-08-31T00:00:00.000Z', to: '2026-09-01T00:00:00.000Z' };

function widget(id: string, title: string, metric: string): WidgetConfig {
  return {
    schemaVersion: 1,
    id,
    title,
    mark: 'line',
    options: {},
    query: {
      metric,
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
  };
}

/**
 * Eight widgets over FOUR metrics — each metric shown twice, which is the
 * ordinary case the design names: the same series appears on a dashboard in
 * more than one framing (a line and a single-stat, two ranges of one board,
 * an overview and a detail).
 */
const FOUR_METRICS = [
  'cache.hit_rate',
  'cache.evictions',
  'cache.memory_used',
  'cache.ops_per_second',
] as const;

const EIGHT_WIDGETS: readonly WidgetConfig[] = FOUR_METRICS.flatMap((metric, index) => [
  widget(`w${String(index * 2 + 1)}`, `${metric} (overview)`, metric),
  widget(`w${String(index * 2 + 2)}`, `${metric} (detail)`, metric),
]);

const EIGHT_LAYOUT: readonly WidgetPlacement[] = EIGHT_WIDGETS.map((config, order) => ({
  widgetId: config.id,
  x: (order % 3) * 4,
  y: Math.floor(order / 3),
  w: 4,
  h: 1,
  order,
}));

/** Counts POSTs to the range-query endpoint, and answers each with an empty-but-valid response. */
function countQueryRequests() {
  const seen: string[] = [];
  server.use(
    http.post('*/console/v1/query/range', async ({ request }) => {
      const body = (await request.json()) as { metric?: string };
      seen.push(body.metric ?? '(no metric)');
      return HttpResponse.json({
        series: [],
        resolution: { requested: 'auto', served: 'raw', bucketSeconds: 60 },
        warnings: [],
      });
    }),
  );
  return seen;
}

function renderDashboard(
  widgets: readonly WidgetConfig[],
  layout: readonly WidgetPlacement[],
  refreshSeconds = 0,
) {
  // One client for the whole dashboard, exactly as the app has: dedup is a
  // property of the shared cache, so a per-widget client would make this
  // test pass while proving nothing.
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return renderWithProviders(
    <MetricsProvider consoleClient={consoleClient}>
      <DashboardGrid
        dashboardId="db_perf"
        revision={0}
        widgets={widgets}
        layout={layout}
        absoluteRange={ABSOLUTE_RANGE}
        editing={false}
        resolution="auto"
        refreshSeconds={refreshSeconds}
      />
    </MetricsProvider>,
    { queryClient },
  );
}

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('query dedup across a dashboard (design §17)', () => {
  it('issues FOUR requests for eight widgets over four metrics', async () => {
    const seen = countQueryRequests();

    renderDashboard(EIGHT_WIDGETS, EIGHT_LAYOUT);

    await waitFor(() => {
      expect(seen.length).toBeGreaterThan(0);
    });
    // Settle: any un-deduped duplicate would have been issued in the same
    // render pass as its twin, so once the count stops moving it is final.
    await waitFor(() => {
      expect(seen).toHaveLength(FOUR_METRICS.length);
    });

    // Not just the count — the right four. A count of 4 could also be four
    // requests for one metric plus three widgets that silently failed to
    // query at all.
    expect([...seen].sort()).toEqual([...FOUR_METRICS].sort());
  });

  it('issues one request per DISTINCT query, not per widget', async () => {
    const seen = countQueryRequests();

    // Same eight widgets, but every one on its own metric: dedup must not
    // be over-eager either. A cache keyed too coarsely would collapse these
    // to fewer than eight and show one metric's data under another's title.
    const distinct = EIGHT_WIDGETS.map((config, index) =>
      widget(config.id, config.title, `cache.distinct_${String(index)}`),
    );

    renderDashboard(distinct, EIGHT_LAYOUT);

    await waitFor(() => {
      expect(seen).toHaveLength(distinct.length);
    });
  });
});

describe('refresh pauses while the tab is hidden (DM-D17, dynamic-metrics §13)', () => {
  it('does not fire a scheduled refetch while visibilityState is hidden', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const seen = countQueryRequests();

    // One widget, polling every 10s.
    const [first] = EIGHT_WIDGETS;
    if (first === undefined) {
      throw new Error('fixture is empty');
    }
    renderDashboard([first], [EIGHT_LAYOUT[0] as WidgetPlacement], 10);

    await waitFor(() => {
      expect(seen).toHaveLength(1);
    });

    // Hide the tab, then let three refresh intervals elapse. TanStack Query
    // suspends `refetchInterval` while the document is hidden unless
    // `refetchIntervalInBackground` is set — which `WidgetFrame` does not
    // set, and this is the test that says so out loud.
    //
    // `visibilityState`, not `hidden`: `focusManager` reads
    // `globalThis.document?.visibilityState !== 'hidden'`
    // (`@tanstack/query-core/build/modern/focusManager.js`). Mocking
    // `document.hidden` instead makes this test fail against a perfectly
    // correct app — verified, not assumed.
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden');
    document.dispatchEvent(new Event('visibilitychange'));

    await act(async () => {
      await vi.advanceTimersByTimeAsync(35_000);
    });

    expect(seen).toHaveLength(1);
  });
});
