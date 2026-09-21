import { describe, expect, it } from 'vitest';

import type { ChartModel, ChartPoint, ChartSeries } from '@contracts/metrics/types';

import { summarizeChart } from './chartSummary';

function point(ts: string, value: number): ChartPoint {
  return { ts, value, lowCoverage: false };
}

function series(overrides: Partial<ChartSeries> = {}): ChartSeries {
  return {
    seriesRef: { instanceId: 'cache-01', placementId: null, sourceKey: null },
    label: 'cache-01',
    state: 'ok',
    replicaComposition: { total: 1, inactive: 0 },
    color: 'steelblue',
    dashPattern: 'solid',
    points: [],
    gaps: [],
    ...overrides,
  };
}

function model(overrides: Partial<ChartModel> = {}): ChartModel {
  return {
    metric: { name: 'cache.evictions', metricType: 'counter', unit: 'count' },
    render: { transform: 'rate' },
    coverage: { requestedFrom: '2026-08-30T00:00:00Z', servedFrom: '2026-08-30T00:00:00Z', truncatedByRetention: false },
    series: [],
    ...overrides,
  };
}

describe('summarizeChart', () => {
  it('carries the metric name and unit straight through', () => {
    const summary = summarizeChart(model({ metric: { name: 'cache.hit_rate', metricType: 'gauge', unit: 'ratio' } }));
    expect(summary.metricName).toBe('cache.hit_rate');
    expect(summary.unit).toBe('ratio');
  });

  it('reports min, max and last per series', () => {
    const summary = summarizeChart(
      model({
        series: [series({ points: [point('2026-08-30T00:00:00Z', 5), point('2026-08-30T00:01:00Z', 1), point('2026-08-30T00:02:00Z', 9)] })],
      }),
    );
    expect(summary.series).toEqual([
      expect.objectContaining({ min: 1, max: 9, last: 9, pointCount: 3 }) as unknown,
    ]);
  });

  it('reports null min/max/last for a series with no points — never a zero-fill', () => {
    const summary = summarizeChart(model({ series: [series({ points: [] })] }));
    expect(summary.series[0]).toMatchObject({ min: null, max: null, last: null, pointCount: 0 });
  });

  it('range-to is the latest point across every series, range-from is the served-from timestamp', () => {
    const summary = summarizeChart(
      model({
        coverage: { requestedFrom: '2026-08-23T00:00:00Z', servedFrom: '2026-08-24T00:00:00Z', truncatedByRetention: true },
        series: [
          series({ label: 'a', points: [point('2026-08-25T00:00:00Z', 1)] }),
          series({ label: 'b', points: [point('2026-08-26T00:00:00Z', 2)] }),
        ],
      }),
    );
    expect(summary.rangeFrom).toBe('2026-08-24T00:00:00Z');
    expect(summary.rangeTo).toBe('2026-08-26T00:00:00Z');
  });

  it('reports a null range when no series has a single rendered point', () => {
    const summary = summarizeChart(model({ series: [series({ points: [] }), series({ label: 'b', points: [] })] }));
    expect(summary.rangeFrom).toBeNull();
    expect(summary.rangeTo).toBeNull();
  });

  it('series count matches the number of series regardless of point counts', () => {
    const summary = summarizeChart(model({ series: [series({ label: 'a' }), series({ label: 'b', points: [point('2026-08-30T00:00:00Z', 1)] })] }));
    expect(summary.seriesCount).toBe(2);
    expect(summary.series).toHaveLength(2);
  });

  it('carries each series state through to the summary', () => {
    const summary = summarizeChart(model({ series: [series({ state: 'type-suspect' })] }));
    expect(summary.series[0]?.state).toBe('type-suspect');
  });
});
