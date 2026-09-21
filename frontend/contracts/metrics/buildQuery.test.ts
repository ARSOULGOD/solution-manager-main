import { describe, expect, it } from 'vitest';

import { buildQuery } from './buildQuery';
import type { QueryCapabilities, WidgetQueryConfig } from './buildQuery';

const NOW = new Date('2026-08-14T12:00:00.000Z');
const fixedNow = (): Date => NOW;

const capabilitiesWithRaw: QueryCapabilities = { features: { forceRaw: true } };
const capabilitiesWithoutRaw: QueryCapabilities = { features: { forceRaw: false } };

const widgetConfig: WidgetQueryConfig = {
  metric: 'cache.evictions',
  selector: { entityType: 'cache-engine', instanceIds: ['a1b2', 'c3d4'] },
  seriesBy: 'instance',
  aggregation: { acrossReplicas: 'auto', acrossInstances: 'none' },
};

describe('buildQuery', () => {
  it('carries the single metric and closed vocabularies straight through (DM-D23)', () => {
    const request = buildQuery(
      widgetConfig,
      { range: { kind: 'relative', preset: '1h' }, resolution: 'auto' },
      capabilitiesWithRaw,
      fixedNow,
    );
    expect(request.queryVersion).toBe(1);
    expect(request.metric).toBe('cache.evictions');
    expect(typeof request.metric).toBe('string');
    expect(request.seriesBy).toBe('instance');
    expect(request.aggregation).toEqual({ acrossReplicas: 'auto', acrossInstances: 'none' });
  });

  it('resolves a relative preset to an absolute window at issue time', () => {
    const request = buildQuery(
      widgetConfig,
      { range: { kind: 'relative', preset: '1h' }, resolution: 'auto' },
      capabilitiesWithRaw,
      fixedNow,
    );
    expect(request.range).toEqual({ from: '2026-08-14T11:00:00.000Z', to: '2026-08-14T12:00:00.000Z' });
  });

  it('resolves the 6mo preset by calendar months, not a fixed day count', () => {
    const request = buildQuery(
      widgetConfig,
      { range: { kind: 'relative', preset: '6mo' }, resolution: 'auto' },
      capabilitiesWithRaw,
      fixedNow,
    );
    expect(request.range).toEqual({ from: '2026-02-14T12:00:00.000Z', to: '2026-08-14T12:00:00.000Z' });
  });

  it('passes an already-absolute range through unchanged', () => {
    const request = buildQuery(
      widgetConfig,
      { range: { kind: 'absolute', from: '2026-01-01T00:00:00Z', to: '2026-01-02T00:00:00Z' }, resolution: 'auto' },
      capabilitiesWithRaw,
      fixedNow,
    );
    expect(request.range).toEqual({ from: '2026-01-01T00:00:00Z', to: '2026-01-02T00:00:00Z' });
  });

  it('defaults an absent label filter to an empty object, never undefined', () => {
    const request = buildQuery(
      widgetConfig,
      { range: { kind: 'relative', preset: '15m' }, resolution: 'auto' },
      capabilitiesWithRaw,
      fixedNow,
    );
    expect(request.selector.labels).toEqual({});
  });

  it('passes raw through when the deployment advertises forceRaw', () => {
    const request = buildQuery(
      widgetConfig,
      { range: { kind: 'relative', preset: '1h' }, resolution: 'raw' },
      capabilitiesWithRaw,
      fixedNow,
    );
    expect(request.resolution).toBe('raw');
  });

  it('fails closed to auto when raw is requested but not advertised', () => {
    const request = buildQuery(
      widgetConfig,
      { range: { kind: 'relative', preset: '1h' }, resolution: 'raw' },
      capabilitiesWithoutRaw,
      fixedNow,
    );
    expect(request.resolution).toBe('auto');
  });

  it('omits groupBy entirely when the caller does not set it', () => {
    const request = buildQuery(
      widgetConfig,
      { range: { kind: 'relative', preset: '1h' }, resolution: 'auto' },
      capabilitiesWithRaw,
      fixedNow,
    );
    expect('groupBy' in request).toBe(false);
  });

  it('carries groupBy through when the caller sets it', () => {
    const request = buildQuery(
      { ...widgetConfig, groupBy: ['region'] },
      { range: { kind: 'relative', preset: '1h' }, resolution: 'auto' },
      capabilitiesWithRaw,
      fixedNow,
    );
    expect(request.groupBy).toEqual(['region']);
  });
});
