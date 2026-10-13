import { describe, expect, it } from 'vitest';

import { buildWidgetConfig, generateWidgetId, type BuildWidgetConfigInput } from './widgetConfig';

const INPUT: BuildWidgetConfigInput = {
  title: 'Evictions — production cache',
  mark: 'line',
  query: {
    metric: 'cache.evictions',
    selector: { entityType: 'cache-engine', instanceIds: ['a1b2'] },
    seriesBy: 'instance',
    aggregation: { acrossReplicas: 'auto', acrossInstances: 'none' },
  },
  range: { kind: 'preset', preset: '24h' },
  resolution: 'auto',
};

describe('generateWidgetId', () => {
  it('mints a `w_` + 12 hex char id, different every call', () => {
    const a = generateWidgetId();
    const b = generateWidgetId();
    expect(a).not.toEqual(b);
    expect(a).toMatch(/^w_[0-9a-f]{12}$/);
  });
});

describe('buildWidgetConfig', () => {
  it('carries every §7 field the task lists', () => {
    const config = buildWidgetConfig(INPUT, 'w_fixed');
    expect(config).toEqual({
      schemaVersion: 1,
      id: 'w_fixed',
      title: 'Evictions — production cache',
      mark: 'line',
      options: {},
      query: INPUT.query,
      range: INPUT.range,
      resolution: 'auto',
      axes: { y: { label: '', unitFormat: 'auto', min: null, max: null } },
      legend: { visible: true, position: 'bottom', maxHeightRows: 4 },
      series: { colorStrategy: 'palette', colors: {} },
      thresholds: [],
      refresh: { intervalSeconds: 60 },
    });
  });

  it('mints an id when none is supplied', () => {
    const config = buildWidgetConfig(INPUT);
    expect(config.id).toMatch(/^w_[0-9a-f]{12}$/);
  });

  it('carries `range`/`resolution` through as DEFAULTS, not resolved to absolute (`DM-D21`)', () => {
    const config = buildWidgetConfig({ ...INPUT, range: { kind: 'custom', from: '2026-01-01T00:00:00.000Z', to: '2026-01-02T00:00:00.000Z' } });
    expect(config.range).toEqual({ kind: 'custom', from: '2026-01-01T00:00:00.000Z', to: '2026-01-02T00:00:00.000Z' });
  });

  it('never invents a `layer` or `scope` field — §7 has none (dashboard placement owns that)', () => {
    const config = buildWidgetConfig(INPUT, 'w_fixed');
    expect(config).not.toHaveProperty('layer');
    expect(config).not.toHaveProperty('scope');
  });
});
