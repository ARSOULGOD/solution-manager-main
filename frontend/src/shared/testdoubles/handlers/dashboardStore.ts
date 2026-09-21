/**
 * The dashboard fixture's mutable state (task 132).
 *
 * Before this, the dashboard handlers were write-only theatre: `GET` returned
 * a static literal, `POST /widgets` echoed the request body back and dropped
 * it, `PUT /layout` and `PATCH /widgets/:id` returned `204` and changed
 * nothing, and `POST /reset` returned `widgets: []` — so "Reset defaults"
 * EMPTIED the board instead of restoring the package layer. An operator could
 * walk Explore → Save as widget → see a success toast → and land on a
 * dashboard that never showed the widget. Holding one document per dashboard
 * id here makes the whole loop land.
 *
 * Same posture as `app/dev/devPackageStore.ts`: module-level state behind
 * named functions, no top-level side effects. Unlike that store this is
 * memory-only — a fixture has no reason to survive a reload, and
 * `sessionStorage` would leak one test run's writes into the next.
 *
 * **Reset between tests is mandatory, not optional.** `server.resetHandlers()`
 * restores handlers, never the module state they close over, so a widget
 * saved by one test would still be on the board in the next.
 * `resetDashboardStore()` is called from `src/test/setup.ts`'s `afterEach`,
 * exactly as `resetConfigGenerations()` already is for the same reason.
 *
 * `shared/testdoubles` may not import `@features/*` (boundaries policy, task
 * 009), so the widget documents below are hand-written literals shaped to
 * task 084's `WidgetConfigV1` + `layer`, never an import of it. Same reason
 * the handler file's own comment already gives.
 */

/** Loose by design: this file may not import the real `WidgetConfigV1` (see header). */
type WidgetDoc = Record<string, unknown> & { id: string };
type Placement = { widgetId: string; x: number; y: number; w: number; h: number; order: number };

interface DashboardDocument {
  id: string;
  schemaVersion: number;
  widgets: WidgetDoc[];
  layout: Placement[];
  range: { preset: string };
  refresh: { intervalSeconds: number | null };
}

/** Every widget below shares this envelope; only the interesting fields differ. */
function widget(
  id: string,
  title: string,
  mark: 'line' | 'gauge' | 'single-stat',
  metric: string,
  extra: Record<string, unknown> = {},
): WidgetDoc {
  return {
    schemaVersion: 1,
    id,
    title,
    mark,
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
    ...extra,
  };
}

/**
 * The six widgets of `reference/app/dashboards.jsx`'s own `DASH`, in its
 * order. The old two-widget fixture (one line, one single-stat) demonstrated
 * nothing: `GaugeMark`, threshold rendering and `HiddenDefaultWidget` were all
 * shipped but unexercised on screen, and every widget was `h: 1`, so task
 * 129's `placement.h` sizing had nothing to show either.
 */
const PACKAGE_DEFAULT_WIDGETS: readonly WidgetDoc[] = Object.freeze([
  widget('w1', 'Hit rate', 'line', 'cache.hit_rate'),
  // A warning threshold — `ChartRenderer` draws it as a `markLine`, dashed as
  // well as coloured (§12/§18: never colour alone).
  // The reference's own threshold is 24, against ITS synthetic data. The
  // fixture's evictions series runs ~12-18, so 24 would sit off-scale and
  // the `markLine` would never be drawn — a threshold that demonstrates
  // nothing is the defect this fixture exists to fix.
  widget('w2', 'Evictions / min', 'line', 'cache.evictions', {
    thresholds: [{ value: 16, label: 'warn', severity: 'warning', style: 'dashed' }],
  }),
  widget('w3', 'Entries', 'single-stat', 'cache.size', { layer: 'shared' }),
  // `DM-D22` — a gauge renders exactly one value. `acrossInstances` stays
  // `'none'`: the query handler refuses cross-instance aggregation over
  // fewer than two instances (`aggregationError` in `console.ts`), and this
  // selector resolves to one, so asking for `'avg'` here made the widget
  // render "This widget failed to load" instead of a gauge. `min`/`max`
  // bound the arc to a ratio rather than `GaugeMark`'s 0-100 default.
  widget('w4', 'Hit rate (now)', 'gauge', 'cache.hit_rate', {
    layer: 'shared',
    axes: { y: { label: '', unitFormat: 'auto', min: 0, max: 1 } },
  }),
  // Hidden, not deleted — renders as `HiddenDefaultWidget`.
  widget('w5', 'p99 GET latency', 'line', 'cache.p99_get_latency', { hidden: true }),
  // Declares a schema this console does not know, so `migrations.ts` routes it
  // to `WidgetFrame`'s read-only "too new" path — whole, never partial.
  widget('w6', 'Miss count', 'line', 'cache.miss_count', { layer: 'shared', schemaVersion: 2 }),
]);

/** Real `{x,y,w,h}` on a 12-column grid, so task 129's row sizing is visible: `h: 2` widgets are twice `h: 1`. */
const PACKAGE_DEFAULT_LAYOUT: readonly Placement[] = Object.freeze([
  { widgetId: 'w1', x: 0, y: 0, w: 6, h: 2, order: 0 },
  { widgetId: 'w2', x: 6, y: 0, w: 6, h: 2, order: 1 },
  { widgetId: 'w3', x: 0, y: 2, w: 3, h: 1, order: 2 },
  { widgetId: 'w4', x: 3, y: 2, w: 3, h: 1, order: 3 },
  { widgetId: 'w5', x: 6, y: 2, w: 6, h: 2, order: 4 },
  { widgetId: 'w6', x: 0, y: 3, w: 6, h: 2, order: 5 },
]);

function seed(dashboardId: string): DashboardDocument {
  return {
    id: dashboardId,
    schemaVersion: 1,
    // Deep-cloned: the frozen seed must survive every mutation below, or
    // "Reset defaults" would restore whatever the last test left behind.
    widgets: PACKAGE_DEFAULT_WIDGETS.map((w) => structuredClone(w)),
    layout: PACKAGE_DEFAULT_LAYOUT.map((p) => ({ ...p })),
    range: { preset: '24h' },
    refresh: { intervalSeconds: null },
  };
}

const dashboards = new Map<string, DashboardDocument>();

function documentFor(dashboardId: string): DashboardDocument {
  const existing = dashboards.get(dashboardId);
  if (existing !== undefined) {
    return existing;
  }
  const created = seed(dashboardId);
  dashboards.set(dashboardId, created);
  return created;
}

export function getDashboard(dashboardId: string): DashboardDocument {
  return documentFor(dashboardId);
}

/**
 * Appends a saved widget and gives it a placement, so it is actually on the
 * board when Explore navigates there. Placed on a fresh row below everything
 * else — a fixture has no packing algorithm and does not need one.
 */
export function addWidget(dashboardId: string, config: Record<string, unknown>): WidgetDoc {
  const doc = documentFor(dashboardId);
  const id = `w_saved_${String(doc.widgets.length + 1)}`;
  const saved: WidgetDoc = { ...config, id, layer: config['layer'] ?? 'shared' };
  doc.widgets.push(saved);
  const nextRow = doc.layout.reduce((max, p) => Math.max(max, p.y + p.h), 0);
  const nextOrder = doc.layout.reduce((max, p) => Math.max(max, p.order + 1), 0);
  doc.layout.push({ widgetId: id, x: 0, y: nextRow, w: 6, h: 2, order: nextOrder });
  return saved;
}

export function setLayout(dashboardId: string, layout: readonly Placement[]): void {
  documentFor(dashboardId).layout = layout.map((p) => ({ ...p }));
}

/** Both directions — hide from `WidgetFrame`'s menu, restore from `HiddenDefaultWidget`. */
export function setWidgetHidden(dashboardId: string, widgetId: string, hidden: boolean): void {
  const target = documentFor(dashboardId).widgets.find((w) => w.id === widgetId);
  if (target !== undefined) {
    target['hidden'] = hidden;
  }
}

/**
 * Back to the PACKAGE defaults — the six widgets above, with `w5` hidden
 * again. Emphatically not `widgets: []`, which is what this used to return
 * and which made "Reset defaults" a destructive action.
 */
export function resetDashboard(dashboardId: string): DashboardDocument {
  const restored = seed(dashboardId);
  dashboards.set(dashboardId, restored);
  return restored;
}

/** Test hook — see this file's header on why `server.resetHandlers()` is not enough. */
export function resetDashboardStore(): void {
  dashboards.clear();
}
