import { describe, expect, it } from 'vitest';

import type { QueryResponse } from '../metricsRepository';

import { deriveExploreResultState, hasNoDataInRange } from './exploreResultState';

function response(overrides: Partial<QueryResponse> = {}): QueryResponse {
  return {
    queryVersion: 1,
    metric: { name: 'cache.hit_rate', metricType: 'gauge', unit: 'ratio' },
    render: { transform: 'none' },
    resolution: { requested: 'auto', served: 'hourly', bucketSeconds: 3600 },
    coverage: { requestedFrom: '2026-08-30T00:00:00Z', servedFrom: '2026-08-30T00:00:00Z', truncatedByRetention: false },
    gapThresholdSeconds: 7200,
    series: [
      {
        seriesRef: { instanceId: 'cache-01', placementId: null, sourceKey: null },
        label: 'cache-01',
        replicaComposition: { total: 1, inactive: 0 },
        state: 'ok',
        points: [['2026-08-30T00:00:00Z', 0.9, 60, 60]],
        gaps: [],
      },
    ],
    partial: { degraded: false, unavailable: [] },
    ...overrides,
  };
}

const BASE = { hasMetric: true, hasInstances: true, isPlaceholderData: false, error: undefined, data: undefined };

describe('deriveExploreResultState', () => {
  it('reports the metric as the missing choice before instances are even consulted', () => {
    const state = deriveExploreResultState({ ...BASE, hasMetric: false, hasInstances: false, status: 'pending' });
    expect(state).toEqual({ kind: 'empty-selection', missing: 'metric' });
  });

  it('reports instances as the missing choice once a metric is chosen', () => {
    const state = deriveExploreResultState({ ...BASE, hasInstances: false, status: 'pending' });
    expect(state).toEqual({ kind: 'empty-selection', missing: 'instances' });
  });

  it('is empty-selection even if a query happened to be mid-flight (disabled queries never reach here)', () => {
    const state = deriveExploreResultState({ ...BASE, hasInstances: false, status: 'success', data: response() });
    expect(state).toEqual({ kind: 'empty-selection', missing: 'instances' });
  });

  it('is loading on the true first fetch — no previous data, status pending', () => {
    const state = deriveExploreResultState({ ...BASE, status: 'pending' });
    expect(state).toEqual({ kind: 'loading' });
  });

  it('carries the thrown error through unexamined on a query failure', () => {
    const error = new Error('boom');
    const state = deriveExploreResultState({ ...BASE, status: 'error', error });
    expect(state).toEqual({ kind: 'error', error });
  });

  it('is a settled result, not refreshing, when isPlaceholderData is false', () => {
    const data = response();
    const state = deriveExploreResultState({ ...BASE, status: 'success', data });
    expect(state).toEqual({ kind: 'result', response: data, refreshing: false });
  });

  it('is refreshing — carrying the STALE previous response — when isPlaceholderData is true', () => {
    const staleData = response({ metric: { name: 'cache.evictions', metricType: 'counter', unit: 'count' } });
    const state = deriveExploreResultState({
      ...BASE,
      status: 'success',
      data: staleData,
      isPlaceholderData: true,
    });
    expect(state).toEqual({ kind: 'result', response: staleData, refreshing: true });
  });
});

describe('hasNoDataInRange', () => {
  it('is false when at least one series has a point', () => {
    expect(hasNoDataInRange(response())).toBe(false);
  });

  it('is true when every series has zero points', () => {
    const empty = response({
      series: [
        {
          seriesRef: { instanceId: 'cache-01', placementId: null, sourceKey: null },
          label: 'cache-01',
          replicaComposition: { total: 1, inactive: 0 },
          state: 'ok',
          points: [],
          gaps: [],
        },
      ],
    });
    expect(hasNoDataInRange(empty)).toBe(true);
  });

  it('is true for zero series (nothing resolved at all)', () => {
    expect(hasNoDataInRange(response({ series: [] }))).toBe(true);
  });
});
