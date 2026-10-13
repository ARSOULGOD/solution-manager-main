import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { describe, expect, it } from 'vitest';

import { FAIL_CLOSED_CAPABILITIES } from './capabilityDefaults';
import type { Capabilities, MetricsRepository } from './metricsRepository';
import { useCapabilities } from './useCapabilities';

const SERVER_CAPABILITIES: Capabilities = {
  queryVersion: 1,
  tiers: [{ name: 'raw', retention: 'P6M', minBucketSeconds: 15 }],
  maxRange: 'P6M',
  maxSeriesPerWidget: 10,
  maxPointsPerSeries: 1000,
  marks: ['line', 'gauge', 'single-stat'],
  aggregations: ['sum', 'avg', 'min', 'max'],
  features: { forceRaw: true, streamingExport: true, pngExport: false },
  collector: { state: 'live', unfrozenClosedPartitions: 0, reasons: [] },
};

function fakeRepository(capabilities: () => Promise<Capabilities>): MetricsRepository {
  return {
    capabilities,
    entityTypes: () => Promise.reject(new Error('not used in this test')),
    instances: () => Promise.reject(new Error('not used in this test')),
    metricCatalog: () => Promise.reject(new Error('not used in this test')),
    labelValues: () => Promise.reject(new Error('not used in this test')),
    queryRange: () => Promise.reject(new Error('not used in this test')),
    queryInstant: () => Promise.reject(new Error('not used in this test')),
    exportRangeUrl: () => '',
    declareMetricType: () => Promise.reject(new Error('not used in this test')),
  };
}

function wrapper() {
  // No retry — a single rejection must settle the query immediately so the
  // fail-closed branch is observable without waiting out queryClient's real
  // 5xx retry policy (task 028's `shouldRetry`, not under test here).
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
}

describe('useCapabilities', () => {
  it('resolves the server capabilities and reports not degraded', async () => {
    const repository = fakeRepository(() => Promise.resolve(SERVER_CAPABILITIES));

    const { result } = renderHook(() => useCapabilities(repository), { wrapper: wrapper() });

    await waitFor(() => expect(result.current.isLoading).toBe(false));

    expect(result.current.capabilities).toEqual(SERVER_CAPABILITIES);
    expect(result.current.degraded).toBe(false);
  });

  it('fails closed to capabilityDefaults, and only those, when the fetch fails', async () => {
    const repository = fakeRepository(() => Promise.reject(new Error('capabilities unavailable')));

    const { result } = renderHook(() => useCapabilities(repository), { wrapper: wrapper() });

    await waitFor(() => expect(result.current.degraded).toBe(true));

    expect(result.current.capabilities).toEqual(FAIL_CLOSED_CAPABILITIES);
  });
});
