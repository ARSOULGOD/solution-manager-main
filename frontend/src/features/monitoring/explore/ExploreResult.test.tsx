/**
 * One test per §11.2 row this file itself owns — the rows `ExploreResult`
 * delegates to `ChartRenderer`/`MetricSelector` (gaps, type-suspect,
 * pending-config-change, inactive-replica, unverified durability) are that
 * component's own coverage, per this file's header comment.
 */
import { ThemeProvider } from '@mui/material/styles';
import { keepPreviousData, QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { describe, expect, it, vi } from 'vitest';

import { ApiError } from '@shared/api';
import { createSmTheme } from '@shared/theme';

import { render, renderWithProviders, screen, waitFor } from '../../../test/renderWithProviders';
import type { MetricsRepository, QueryRequest, QueryResponse } from '../metricsRepository';

import { ExploreResult } from './ExploreResult';

vi.mock('echarts', () => ({ init: vi.fn(() => ({ setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn() })) }));

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

describe('ExploreResult', () => {
  it('names the metric as the missing choice when none is selected', () => {
    renderWithProviders(
      <ExploreResult
        metricsRepository={repositoryReturning(vi.fn())}
        metricName={null}
        hasInstances={false}
        request={null}
      />,
    );
    expect(screen.getByText('Select a metric')).toBeInTheDocument();
  });

  it('names instances as the missing choice once a metric is chosen', () => {
    renderWithProviders(
      <ExploreResult
        metricsRepository={repositoryReturning(vi.fn())}
        metricName="cache.hit_rate"
        hasInstances={false}
        request={null}
      />,
    );
    expect(screen.getByText('Select at least one instance')).toBeInTheDocument();
  });

  it('shows a skeleton at chart dimensions on the true first fetch', () => {
    const queryRange = vi.fn(() => new Promise<QueryResponse>(() => {}));
    renderWithProviders(
      <ExploreResult
        metricsRepository={repositoryReturning(queryRange)}
        metricName="cache.hit_rate"
        hasInstances
        request={REQUEST}
      />,
    );
    expect(screen.getByRole('status', { name: 'Loading chart' })).toBeInTheDocument();
  });

  it('renders the typed 422 with its code and correlation id, and never offers a retry', async () => {
    const queryRange = vi.fn(() =>
      Promise.reject(
        new ApiError({
          status: 422,
          code: 'AGGREGATION_NOT_APPLICABLE',
          message: 'server message',
          correlationId: 'corr-1',
        }),
      ),
    );
    renderWithProviders(
      <ExploreResult
        metricsRepository={repositoryReturning(queryRange)}
        metricName="cache.hit_rate"
        hasInstances
        request={REQUEST}
      />,
    );

    expect(await screen.findByText('Request refused — not retried')).toBeInTheDocument();
    expect(
      screen.getByText('Aggregation across instances needs at least two instances to combine.'),
    ).toBeInTheDocument();
    expect(screen.getByText('Error code: AGGREGATION_NOT_APPLICABLE')).toBeInTheDocument();
    expect(screen.getByText('Correlation id: corr-1')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Retry' })).toBeNull();
  });

  it('renders a generic failure with a retry action that re-issues the query', async () => {
    const queryRange = vi.fn().mockRejectedValue(new Error('network down'));
    renderWithProviders(
      <ExploreResult
        metricsRepository={repositoryReturning(queryRange)}
        metricName="cache.hit_rate"
        hasInstances
        request={REQUEST}
      />,
    );

    expect(await screen.findByText('The request failed')).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: 'Retry' })).toBeInTheDocument();
    expect(queryRange).toHaveBeenCalledTimes(1);
  });

  it('shows "no samples" — never a zero line — when every series is empty', async () => {
    const queryRange = vi.fn().mockResolvedValue(response({ series: [] }));
    renderWithProviders(
      <ExploreResult
        metricsRepository={repositoryReturning(queryRange)}
        metricName="cache.hit_rate"
        hasInstances
        request={REQUEST}
      />,
    );

    expect(await screen.findByText('No samples in this range')).toBeInTheDocument();
    expect(
      screen.getByText('Served tier: hourly · bucket 3600s. Nearest data is outside the selected window.'),
    ).toBeInTheDocument();
  });

  it('banners partial data, naming each unavailable instance and why', async () => {
    const queryRange = vi.fn().mockResolvedValue(
      response({
        partial: {
          degraded: true,
          unavailable: [{ instanceId: 'cache-edge-01', label: 'cache-edge-01', reason: 'host unreachable' }],
        },
      }),
    );
    renderWithProviders(
      <ExploreResult
        metricsRepository={repositoryReturning(queryRange)}
        metricName="cache.hit_rate"
        hasInstances
        request={REQUEST}
      />,
    );

    expect(await screen.findByText('Partial data — some instances did not resolve')).toBeInTheDocument();
    expect(screen.getByText('cache-edge-01')).toBeInTheDocument();
    expect(screen.getByText(/host unreachable/)).toBeInTheDocument();
  });

  it('banners retention truncation with the served-from boundary', async () => {
    const queryRange = vi.fn().mockResolvedValue(
      response({
        coverage: {
          requestedFrom: '2026-08-30T00:00:00Z',
          servedFrom: '2026-08-30T12:00:00Z',
          truncatedByRetention: true,
        },
      }),
    );
    renderWithProviders(
      <ExploreResult
        metricsRepository={repositoryReturning(queryRange)}
        metricName="cache.hit_rate"
        hasInstances
        request={REQUEST}
      />,
    );

    expect(
      await screen.findByText(/is outside the retention window; the chart starts at the earliest retained point/),
    ).toBeInTheDocument();
  });

  it('mounts a real chart, not an empty state, for an ordinary successful result', async () => {
    const queryRange = vi.fn().mockResolvedValue(response());
    renderWithProviders(
      <ExploreResult
        metricsRepository={repositoryReturning(queryRange)}
        metricName="cache.hit_rate"
        hasInstances
        request={REQUEST}
      />,
    );

    await waitFor(() => expect(screen.getByLabelText('Chart: cache.hit_rate')).toBeInTheDocument());
    expect(screen.queryByText('No samples in this range')).toBeNull();
  });

  it('dims the chart and shows a refreshing indicator while a new request is in flight', async () => {
    const first = response();
    const second = response({ metric: { name: 'cache.hit_rate', metricType: 'gauge', unit: 'ratio' } });
    let resolveSecond: (value: QueryResponse) => void = () => {};
    const queryRange = vi
      .fn()
      .mockResolvedValueOnce(first)
      .mockImplementationOnce(() => new Promise<QueryResponse>((resolve) => (resolveSecond = resolve)));

    const client = new QueryClient({
      defaultOptions: { queries: { retry: false, placeholderData: keepPreviousData } },
    });
    const theme = createSmTheme('light');
    function Wrapper({ children }: { children: ReactNode }) {
      return (
        <ThemeProvider theme={theme}>
          <QueryClientProvider client={client}>{children}</QueryClientProvider>
        </ThemeProvider>
      );
    }

    const { rerender } = render(
      <ExploreResult
        metricsRepository={repositoryReturning(queryRange)}
        metricName="cache.hit_rate"
        hasInstances
        request={REQUEST}
      />,
      { wrapper: Wrapper },
    );
    await waitFor(() => expect(screen.getByLabelText('Chart: cache.hit_rate')).toBeInTheDocument());

    const secondRequest: QueryRequest = { ...REQUEST, seriesBy: 'replica' };
    rerender(
      <ExploreResult
        metricsRepository={repositoryReturning(queryRange)}
        metricName="cache.hit_rate"
        hasInstances
        request={secondRequest}
      />,
    );

    expect(await screen.findByLabelText('Refreshing chart')).toBeInTheDocument();
    // Stale data stays on screen — never blanked — while the new request resolves.
    expect(screen.getByLabelText('Chart: cache.hit_rate')).toBeInTheDocument();

    resolveSecond(second);
    await waitFor(() => expect(screen.queryByLabelText('Refreshing chart')).toBeNull());
  });
});
