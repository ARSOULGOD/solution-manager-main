import { axe } from 'jest-axe';
import { describe, expect, it, vi } from 'vitest';

import { ApiError } from '@shared/api';

import { renderWithProviders, screen, waitFor } from '../../../test/renderWithProviders';
import type { MetricsRepository, QueryRequest, QueryResponse } from '../metricsRepository';

import { ExploreResult } from './ExploreResult';

vi.mock('echarts', () => ({ init: vi.fn(() => ({ setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn() })) }));

const modes = ['light', 'dark'] as const;

const REQUEST: QueryRequest = {
  queryVersion: 1,
  metric: 'cache.hit_rate',
  selector: { entityType: 'cache-instance', instanceIds: ['cache-01'], labels: {} },
  range: { from: '2026-08-30T00:00:00.000Z', to: '2026-08-31T00:00:00.000Z' },
  resolution: 'auto',
  seriesBy: 'instance',
  aggregation: { acrossReplicas: 'auto', acrossInstances: 'none' },
};

function response(overrides: Partial<QueryResponse> = {}): QueryResponse {
  return {
    queryVersion: 1,
    metric: { name: 'cache.hit_rate', metricType: 'gauge', unit: 'ratio' },
    render: { transform: 'none' },
    resolution: { requested: 'auto', served: 'hourly', bucketSeconds: 3600 },
    coverage: {
      requestedFrom: '2026-08-30T00:00:00Z',
      servedFrom: '2026-08-30T00:00:00Z',
      truncatedByRetention: false,
    },
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

function repositoryReturning(queryRange: MetricsRepository['queryRange']): Pick<MetricsRepository, 'queryRange'> {
  return { queryRange };
}

describe('ExploreResult a11y', () => {
  it.each(modes)('empty selection has no violations, in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <ExploreResult metricsRepository={repositoryReturning(vi.fn())} metricName={null} hasInstances={false} request={null} />,
      { mode },
    );
    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('the typed 422 has no violations, in %s mode', async (mode) => {
    const queryRange = vi.fn(() =>
      Promise.reject(new ApiError({ status: 422, code: 'AGGREGATION_NOT_APPLICABLE', message: 'server message' })),
    );
    const { container } = renderWithProviders(
      <ExploreResult
        metricsRepository={repositoryReturning(queryRange)}
        metricName="cache.hit_rate"
        hasInstances
        request={REQUEST}
      />,
      { mode },
    );
    await screen.findByText('Request refused — not retried');
    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('the partial-data banner plus chart has no violations, in %s mode', async (mode) => {
    const queryRange = vi.fn().mockResolvedValue(
      response({
        partial: {
          degraded: true,
          unavailable: [{ instanceId: 'cache-edge-01', label: 'cache-edge-01', reason: 'host unreachable' }],
        },
      }),
    );
    const { container } = renderWithProviders(
      <ExploreResult
        metricsRepository={repositoryReturning(queryRange)}
        metricName="cache.hit_rate"
        hasInstances
        request={REQUEST}
      />,
      { mode },
    );
    await waitFor(() => expect(screen.getByLabelText('Chart: cache.hit_rate')).toBeInTheDocument());
    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('the no-data empty box has no violations, in %s mode', async (mode) => {
    const queryRange = vi.fn().mockResolvedValue(response({ series: [] }));
    const { container } = renderWithProviders(
      <ExploreResult
        metricsRepository={repositoryReturning(queryRange)}
        metricName="cache.hit_rate"
        hasInstances
        request={REQUEST}
      />,
      { mode },
    );
    await screen.findByText('No samples in this range');
    expect(await axe(container)).toHaveNoViolations();
  });
});
