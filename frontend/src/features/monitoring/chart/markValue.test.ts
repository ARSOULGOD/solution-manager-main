import { describe, expect, it } from 'vitest';

import type { ChartModel } from '@contracts/metrics/types';

import { resolveMarkValue } from './markValue';

function model(overrides: Partial<ChartModel> = {}): ChartModel {
  return {
    metric: { name: 'cache.hit_rate', metricType: 'gauge', unit: 'ratio' },
    render: { transform: 'none' },
    coverage: { requestedFrom: '2026-08-30T00:00:00Z', servedFrom: '2026-08-30T00:00:00Z', truncatedByRetention: false },
    series: [],
    ...overrides,
  };
}

describe('resolveMarkValue', () => {
  it('returns null when there is no series', () => {
    expect(resolveMarkValue(model({ series: [] }))).toBeNull();
  });

  it('returns null when the aggregated series has no rendered point — never a zero', () => {
    const chartModel = model({
      series: [
        {
          seriesRef: { instanceId: 'a', placementId: null, sourceKey: null },
          label: 'a',
          state: 'ok',
          replicaComposition: { total: 1, inactive: 0 },
          color: 'steelblue',
          dashPattern: 'solid',
          points: [],
          gaps: [],
        },
      ],
    });
    expect(resolveMarkValue(chartModel)).toBeNull();
  });

  it('returns the last point of the aggregated series, with the chart-wide unit', () => {
    const chartModel = model({
      series: [
        {
          seriesRef: { instanceId: 'a', placementId: null, sourceKey: null },
          label: 'aggregate · 3 instances',
          state: 'ok',
          replicaComposition: { total: 1, inactive: 0 },
          color: 'steelblue',
          dashPattern: 'solid',
          points: [
            { ts: '2026-08-30T00:00:00Z', value: 0.5, lowCoverage: false },
            { ts: '2026-08-30T00:01:00Z', value: 0.87, lowCoverage: false },
          ],
          gaps: [],
        },
      ],
    });
    expect(resolveMarkValue(chartModel)).toEqual({
      point: { ts: '2026-08-30T00:01:00Z', value: 0.87, lowCoverage: false },
      unit: 'ratio',
    });
  });
});
