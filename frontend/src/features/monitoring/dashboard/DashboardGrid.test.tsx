import userEvent from '@testing-library/user-event';
import { HttpResponse, http } from 'msw';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { createConsoleClient } from '@shared/api';
import { server } from '@shared/testdoubles/server';
import { reflowFloorPx } from '@shared/theme/breakpoints';

import { setViewportWidth } from '../../../test/matchMedia';
import { assertReflow } from '../../../test/reflow';
import { renderWithProviders, screen, waitFor } from '../../../test/renderWithProviders';
import type { WidgetConfig, WidgetPlacement } from '../dashboardRepository';
import { MetricsProvider } from '../MetricsProvider';

import { DashboardGrid } from './DashboardGrid';
import type { WidgetFrameProps } from './WidgetFrame';

// Isolates `DashboardGrid`'s OWN contract — DOM order, grid placement,
// edit-mode wiring, move persistence — from `WidgetFrame`'s query/render
// machinery, which has its own test file.
vi.mock('./WidgetFrame', () => ({
  WidgetFrame: (props: WidgetFrameProps) => (
    <div data-testid={`widget-${props.widget.id}`}>
      {props.widget.title}
      {props.editing && (
        <>
          <button onClick={props.onMoveEarlier} disabled={!props.canMoveEarlier}>
            {`move earlier: ${props.widget.title}`}
          </button>
          <button onClick={props.onMoveLater} disabled={!props.canMoveLater}>
            {`move later: ${props.widget.title}`}
          </button>
        </>
      )}
      <button onClick={props.onHide} disabled={props.hidePending}>
        {`hide: ${props.widget.title}`}
      </button>
    </div>
  ),
}));

const consoleClient = createConsoleClient('/api');

function widget(id: string, title: string, overrides: Partial<WidgetConfig> = {}): WidgetConfig {
  return {
    schemaVersion: 1,
    id,
    title,
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

const WIDGETS: readonly WidgetConfig[] = [
  widget('w1', 'First'),
  widget('w2', 'Second'),
  widget('w3', 'Third'),
];

const LAYOUT: readonly WidgetPlacement[] = [
  { widgetId: 'w1', x: 0, y: 0, w: 4, h: 1, order: 0 },
  { widgetId: 'w2', x: 4, y: 0, w: 4, h: 1, order: 1 },
  { widgetId: 'w3', x: 8, y: 0, w: 4, h: 1, order: 2 },
];

const ABSOLUTE_RANGE = { from: '2026-08-31T00:00:00.000Z', to: '2026-09-01T00:00:00.000Z' };

function renderGrid(overrides: Partial<Parameters<typeof DashboardGrid>[0]> = {}) {
  return renderWithProviders(
    <MetricsProvider consoleClient={consoleClient}>
      <DashboardGrid
        dashboardId="db_1"
        revision={0}
        widgets={WIDGETS}
        layout={LAYOUT}
        absoluteRange={ABSOLUTE_RANGE}
        resolution="auto"
        refreshSeconds={0}
        editing={false}
        {...overrides}
      />
    </MetricsProvider>,
  );
}

afterEach(() => {
  vi.restoreAllMocks();
});

describe('DashboardGrid', () => {
  it('renders every widget in the given (order-sorted) sequence', () => {
    renderGrid();
    const rendered = screen.getAllByTestId(/^widget-/).map((el) => el.getAttribute('data-testid'));
    expect(rendered).toEqual(['widget-w1', 'widget-w2', 'widget-w3']);
  });

  it('has no move controls outside edit mode', () => {
    renderGrid();
    expect(screen.queryByText(/move earlier/)).toBeNull();
    expect(screen.queryByText(/move later/)).toBeNull();
  });

  /**
   * Task 131 moved the "Edit layout" toggle onto `DashboardToolbar` and
   * made `editing` a prop of this component, so the mode is driven
   * directly here rather than by clicking a button this component no
   * longer owns. The toggle itself is covered in `DashboardView.test.tsx`.
   */
  it('shows move-earlier/move-later while editing, disabled at the ends', () => {
    renderGrid({ editing: true });

    expect(screen.getByRole('button', { name: 'move earlier: First' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'move later: First' })).not.toBeDisabled();
    expect(screen.getByRole('button', { name: 'move earlier: Second' })).not.toBeDisabled();
    expect(screen.getByRole('button', { name: 'move later: Second' })).not.toBeDisabled();
    expect(screen.getByRole('button', { name: 'move earlier: Third' })).not.toBeDisabled();
    expect(screen.getByRole('button', { name: 'move later: Third' })).toBeDisabled();
  });

  it('moving a widget later swaps its order with its visible neighbour and persists via setLayout', async () => {
    const user = userEvent.setup();
    let capturedBody: unknown = null;
    server.use(
      http.put('*/console/v1/dashboards/:dashboardId/layout', async ({ request }) => {
        capturedBody = await request.json();
        return new HttpResponse(null, { status: 204 });
      }),
    );

    renderGrid({ editing: true });
    await user.click(screen.getByRole('button', { name: 'move later: First' }));

    await waitFor(() => {
      expect(capturedBody).not.toBeNull();
    });
    expect(capturedBody).toEqual({
      layout: [
        { widgetId: 'w1', x: 0, y: 0, w: 4, h: 1, order: 1 },
        { widgetId: 'w2', x: 4, y: 0, w: 4, h: 1, order: 0 },
        { widgetId: 'w3', x: 8, y: 0, w: 4, h: 1, order: 2 },
      ],
    });
  });

  it('renders a hidden default as a restorable placeholder, not a live WidgetFrame', () => {
    const widgets = [widget('w1', 'First'), widget('w2', 'Second', { hidden: true }), widget('w3', 'Third')];
    renderGrid({ widgets });

    expect(screen.queryByTestId('widget-w2')).toBeNull();
    expect(screen.getByText('Hidden default · Second')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Restore' })).toBeInTheDocument();
  });

  it('clicking Restore calls hideDefault(hidden: false) for that widget and refreshes the dashboard', async () => {
    const user = userEvent.setup();
    let captured: { dashboardId: string; widgetId: string; body: unknown } | null = null;
    server.use(
      http.patch('*/console/v1/dashboards/:dashboardId/widgets/:widgetId', async ({ params, request }) => {
        captured = {
          dashboardId: String(params['dashboardId']),
          widgetId: String(params['widgetId']),
          body: await request.json(),
        };
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const widgets = [widget('w1', 'First'), widget('w2', 'Second', { hidden: true })];
    renderGrid({ widgets });

    await user.click(screen.getByRole('button', { name: 'Restore' }));

    await waitFor(() => {
      expect(captured).not.toBeNull();
    });
    expect(captured).toEqual({ dashboardId: 'db_1', widgetId: 'w2', body: { hidden: false } });
  });

  it('clicking Hide on a live widget calls hideDefault(hidden: true) for that widget', async () => {
    const user = userEvent.setup();
    let captured: { widgetId: string; body: unknown } | null = null;
    server.use(
      http.patch('*/console/v1/dashboards/:dashboardId/widgets/:widgetId', async ({ params, request }) => {
        captured = { widgetId: String(params['widgetId']), body: await request.json() };
        return new HttpResponse(null, { status: 204 });
      }),
    );
    renderGrid();

    await user.click(screen.getByRole('button', { name: 'hide: First' }));

    await waitFor(() => {
      expect(captured).not.toBeNull();
    });
    expect(captured).toEqual({ widgetId: 'w1', body: { hidden: true } });
  });

  it('skips a hidden widget when resolving adjacency for move-earlier/move-later', async () => {
    const user = userEvent.setup();
    let capturedBody: unknown = null;
    server.use(
      http.put('*/console/v1/dashboards/:dashboardId/layout', async ({ request }) => {
        capturedBody = await request.json();
        return new HttpResponse(null, { status: 204 });
      }),
    );
    // 'Second' is hidden — moving 'First' later must swap with 'Third', its
    // nearest VISIBLE neighbour, never with the hidden widget between them.
    const widgets = [widget('w1', 'First'), widget('w2', 'Second', { hidden: true }), widget('w3', 'Third')];
    renderGrid({ widgets, editing: true });

    await user.click(screen.getByRole('button', { name: 'move later: First' }));

    await waitFor(() => {
      expect(capturedBody).not.toBeNull();
    });
    expect(capturedBody).toEqual({
      layout: [
        { widgetId: 'w1', x: 0, y: 0, w: 4, h: 1, order: 2 },
        { widgetId: 'w2', x: 4, y: 0, w: 4, h: 1, order: 1 },
        { widgetId: 'w3', x: 8, y: 0, w: 4, h: 1, order: 0 },
      ],
    });
  });

  // "Reset defaults" moved to `DashboardToolbar` in task 131 — its test
  // moved with it, to `DashboardView.test.tsx`.
});

describe('DashboardGrid responsive columns (task 109, FE-D29)', () => {
  it('renders widgets in the same order-sorted DOM sequence at the 320px reflow floor, no horizontal overflow', () => {
    setViewportWidth(reflowFloorPx);
    const { container } = renderGrid();

    const rendered = screen.getAllByTestId(/^widget-/).map((el) => el.getAttribute('data-testid'));
    // Unchanged from the desktop case — the responsive column count (task
    // 109) is a CSS-only concern (`[theme.breakpoints...]` on `sx`, the
    // same mechanism `ExplorePage`'s two-column-at-`lg` layout already
    // uses); it never touches which order these land in the DOM, which is
    // what keyboard/reader traversal (and this assertion) actually walks.
    expect(rendered).toEqual(['widget-w1', 'widget-w2', 'widget-w3']);

    const root = container.querySelector('[data-testid^="widget-"]')?.parentElement?.parentElement;
    expect(root).toBeInstanceOf(HTMLElement);
    if (root instanceof HTMLElement) {
      expect(() => {
        assertReflow(root, reflowFloorPx);
      }).not.toThrow();
    }
  });
});
