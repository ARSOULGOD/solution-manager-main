import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { describe, expect, it } from 'vitest';

import { createConsoleClient } from '@shared/api';

import { MetricsProvider } from './MetricsProvider';
import { useEntityTypes, useLabelValues, useMetricCatalog, useMetricInstances } from './useCatalog';

const consoleClient = createConsoleClient('/api');

function wrapper() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>
      <MetricsProvider consoleClient={consoleClient}>{children}</MetricsProvider>
    </QueryClientProvider>
  );
}

describe('useEntityTypes', () => {
  it('resolves the entity-types route unchanged', async () => {
    const { result } = renderHook(() => useEntityTypes(), { wrapper: wrapper() });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(result.current.data).toEqual([{ id: 'cache-instance', label: 'Cache instance' }]);
  });
});

describe('useMetricInstances', () => {
  it('resolves instances for the given entity type', async () => {
    const { result } = renderHook(() => useMetricInstances('cache-instance'), { wrapper: wrapper() });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(result.current.data?.total).toBeGreaterThan(0);
    expect(result.current.data?.items.length).toBe(result.current.data?.total);
    // `replicaCount` (task 078, `DM-D7`) — required for the series-cap
    // projection and not filtered/inferred by this hook.
    for (const item of result.current.data?.items ?? []) {
      expect(item.replicaCount).toBeGreaterThan(0);
    }
  });

  it('stays disabled — issues no fetch — for an empty entity type', () => {
    const { result } = renderHook(() => useMetricInstances(''), { wrapper: wrapper() });

    expect(result.current.fetchStatus).toBe('idle');
    expect(result.current.data).toBeUndefined();
  });
});

describe('useMetricCatalog', () => {
  it('returns every state side by side — none filtered, none inferred', async () => {
    const { result } = renderHook(() => useMetricCatalog(), { wrapper: wrapper() });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    const entries = result.current.data ?? [];
    const states = entries.map((e) => e.state);
    expect(states).toContain('plottable');
    expect(states).toContain('needs-typing');
    expect(states).toContain('type-suspect');

    // `SM-D30` — an untyped metric's type is never guessed by this hook.
    const untyped = entries.find((e) => e.state === 'needs-typing');
    expect(untyped?.metricType).toBeNull();
  });
});

describe('useLabelValues', () => {
  it('resolves values for a known label key', async () => {
    const { result } = renderHook(() => useLabelValues('region'), { wrapper: wrapper() });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(result.current.data).toEqual(['us-east', 'us-west', 'eu-west']);
  });

  it('stays disabled — issues no fetch — for an empty label key', () => {
    const { result } = renderHook(() => useLabelValues(''), { wrapper: wrapper() });

    expect(result.current.fetchStatus).toBe('idle');
    expect(result.current.data).toBeUndefined();
  });
});
