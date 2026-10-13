import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, renderHook, waitFor } from '@testing-library/react';
import { HttpResponse, http } from 'msw';
import type { ReactNode } from 'react';
import { describe, expect, it, vi } from 'vitest';

import { createConsoleClient } from '@shared/api';
import { server } from '@shared/testdoubles/server';

import { FAIL_CLOSED_CAPABILITIES } from './capabilityDefaults';
import { MetricsProvider, useMetrics } from './MetricsProvider';

const consoleClient = createConsoleClient('/api');

function wrapper() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>
      <MetricsProvider consoleClient={consoleClient}>{children}</MetricsProvider>
    </QueryClientProvider>
  );
}

describe('MetricsProvider / useMetrics', () => {
  it('exposes the fetched capabilities, not degraded, once the request resolves', async () => {
    const { result } = renderHook(() => useMetrics(), { wrapper: wrapper() });

    await waitFor(() => expect(result.current.isLoading).toBe(false));

    expect(result.current.degraded).toBe(false);
    expect(result.current.capabilities.maxSeriesPerWidget).toBeGreaterThan(0);
  });

  it('falls back to fail-closed capabilities and reports degraded when /capabilities errors', async () => {
    server.use(http.get('*/console/v1/capabilities', () => HttpResponse.json(null, { status: 500 })));

    const { result } = renderHook(() => useMetrics(), { wrapper: wrapper() });

    await waitFor(() => expect(result.current.degraded).toBe(true));

    expect(result.current.capabilities).toEqual(FAIL_CLOSED_CAPABILITIES);
  });

  it('useMetrics() throws when called outside a MetricsProvider', () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    function Broken() {
      useMetrics();
      return null;
    }
    expect(() => render(<Broken />)).toThrow('useMetrics() called outside a MetricsProvider.');
    spy.mockRestore();
  });
});
