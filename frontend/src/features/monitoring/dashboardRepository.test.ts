import { describe, expect, it } from 'vitest';

import { createConsoleClient } from '@shared/api';

import { createDashboardRepository, defaultDashboardId, type WidgetConfig } from './dashboardRepository';

const consoleClient = createConsoleClient('/api');

const WIDGET: WidgetConfig = {
  schemaVersion: 1,
  id: 'w_test',
  title: 'Hit rate',
  mark: 'line',
  options: {},
  query: {
    metric: 'cache.hit_rate',
    selector: { entityType: 'cache-engine', instanceIds: ['cache-prod-01'] },
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
  layer: 'shared',
};

describe('createDashboardRepository (fixture-backed)', () => {
  it('reads a dashboard document', async () => {
    const repo = createDashboardRepository(consoleClient);

    const doc = await repo.get('default');

    expect(doc.widgets.length).toBeGreaterThan(0);
  });

  it('saveWidget resolves the saved widget plus a server correlation id (`FE-D13`)', async () => {
    const repo = createDashboardRepository(consoleClient);

    const result = await repo.saveWidget('default', WIDGET);

    // The id is the SERVER's to assign — task 132 made the fixture behave
    // that way instead of echoing back whatever was posted. Everything the
    // caller authored survives the round trip.
    expect(result.widget).toMatchObject({
      title: WIDGET.title,
      mark: WIDGET.mark,
      query: WIDGET.query,
      layer: WIDGET.layer,
    });
    expect(result.widget.id.length).toBeGreaterThan(0);
    expect(result.correlationId).toEqual(expect.any(String));
    expect(result.correlationId.length).toBeGreaterThan(0);
  });

  /**
   * The defect task 132 closes: `POST /widgets` echoed the request and
   * discarded it, so a saved widget never appeared on the board. An
   * operator saw a success toast and an unchanged dashboard.
   */
  it('a saved widget is actually on the board afterwards, with a placement', async () => {
    const repo = createDashboardRepository(consoleClient);
    const before = await repo.get('default');

    const { widget } = await repo.saveWidget('default', WIDGET);
    const after = await repo.get('default');

    expect(after.widgets).toHaveLength(before.widgets.length + 1);
    expect(after.widgets.map((w) => w.id)).toContain(widget.id);
    // Without a placement `DashboardGrid` skips the widget entirely.
    expect(after.layout.map((p) => p.widgetId)).toContain(widget.id);
  });

  /**
   * `POST /reset` used to return `widgets: []`, making "Reset defaults" a
   * destructive action rather than a restorative one.
   */
  it('resetDefaults restores the package widgets rather than emptying the board', async () => {
    const repo = createDashboardRepository(consoleClient);
    const packaged = await repo.get('default');
    await repo.saveWidget('default', WIDGET);
    expect((await repo.get('default')).widgets).toHaveLength(packaged.widgets.length + 1);

    await repo.resetDefaults('default');

    const restored = await repo.get('default');
    expect(restored.widgets).toHaveLength(packaged.widgets.length);
    expect(restored.widgets.map((w) => w.id)).toEqual(packaged.widgets.map((w) => w.id));
  });

  it('hideDefault flips `hidden` and the change survives a re-read', async () => {
    const repo = createDashboardRepository(consoleClient);
    const hiddenByDefault = (await repo.get('default')).widgets.find((w) => w.hidden === true);
    expect(hiddenByDefault).toBeDefined();

    await repo.hideDefault('default', hiddenByDefault?.id ?? '', false);

    const after = await repo.get('default');
    expect(after.widgets.find((w) => w.id === hiddenByDefault?.id)?.hidden).toBe(false);
  });

  it('setLayout persists the layout it is given', async () => {
    const repo = createDashboardRepository(consoleClient);
    const doc = await repo.get('default');
    const moved = doc.layout.map((p, index) => ({ ...p, order: doc.layout.length - index }));

    await repo.setLayout('default', moved);

    expect((await repo.get('default')).layout.map((p) => p.order)).toEqual(
      moved.map((p) => p.order),
    );
  });

  /** The six of `reference/app/dashboards.jsx` — see `dashboardStore.ts`. */
  it('seeds the reference dashboard: gauge, threshold, hidden default and a too-new widget', async () => {
    const repo = createDashboardRepository(consoleClient);

    const doc = await repo.get('default');

    expect(doc.widgets).toHaveLength(6);
    expect(doc.widgets.some((w) => w.mark === 'gauge')).toBe(true);
    expect(doc.widgets.some((w) => w.thresholds.length > 0)).toBe(true);
    expect(doc.widgets.some((w) => w.hidden === true)).toBe(true);
    expect(doc.widgets.some((w) => w.schemaVersion > 1)).toBe(true);
    // Task 129's row sizing only shows if `h` actually varies.
    expect(new Set(doc.layout.map((p) => p.h)).size).toBeGreaterThan(1);
  });
});

describe('defaultDashboardId', () => {
  it('is deterministic from the instance id alone', () => {
    expect(defaultDashboardId('cache-prod-01')).toBe('db_cache-prod-01');
    expect(defaultDashboardId('cache-prod-01')).toBe(defaultDashboardId('cache-prod-01'));
  });
});
