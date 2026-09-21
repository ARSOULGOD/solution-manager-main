/**
 * The capabilities context every Monitoring component reads (task 068, `DM-D10`,
 * `FE-D20`, design §11). Builds one `MetricsRepository` from the `consoleClient`
 * it is given and exposes `useCapabilities`'s result through context so every
 * widget, selector and preset list (tasks 069+) shares one fetch instead of each
 * issuing its own — mirrors `AuthProvider`'s context+hook shape (task 030).
 *
 * Scoped to the Monitoring routes, not mounted in `app/providers.tsx`: unlike
 * auth, capabilities are meaningless outside an instance's Monitoring category,
 * so the route that first needs them constructs this the same way
 * `InstanceCategoryRoute` already constructs `governanceRepository` etc.
 *
 * `dashboardRepository` (task 084) is built from the SAME `consoleClient`
 * here rather than threaded through routes as a second prop — `SaveWidgetDialog`
 * (Explore) and the future `DashboardView` (task 085) are both Monitoring
 * screens under this one provider, so one construction site is enough.
 */
import { createContext, useContext, useMemo, type ReactNode } from 'react';

import type { PlaneClient } from '@shared/api';

import { createDashboardRepository, type DashboardRepository } from './dashboardRepository';
import { createMetricsRepository, type Capabilities, type MetricsRepository } from './metricsRepository';
import { useCapabilities } from './useCapabilities';

export interface MetricsContextValue {
  metricsRepository: MetricsRepository;
  dashboardRepository: DashboardRepository;
  capabilities: Capabilities;
  /** True once `capabilities` fell back to fail-closed defaults (`dynamic-metrics` §17). */
  degraded: boolean;
  isLoading: boolean;
}

const MetricsContext = createContext<MetricsContextValue | null>(null);

export interface MetricsProviderProps {
  consoleClient: PlaneClient;
  children: ReactNode;
}

export function MetricsProvider({ consoleClient, children }: MetricsProviderProps) {
  const metricsRepository = useMemo(() => createMetricsRepository(consoleClient), [consoleClient]);
  const dashboardRepository = useMemo(() => createDashboardRepository(consoleClient), [consoleClient]);
  const { capabilities, degraded, isLoading } = useCapabilities(metricsRepository);

  const value = useMemo<MetricsContextValue>(
    () => ({ metricsRepository, dashboardRepository, capabilities, degraded, isLoading }),
    [metricsRepository, dashboardRepository, capabilities, degraded, isLoading],
  );

  return <MetricsContext.Provider value={value}>{children}</MetricsContext.Provider>;
}

export function useMetrics(): MetricsContextValue {
  const value = useContext(MetricsContext);
  if (value === null) {
    throw new Error('useMetrics() called outside a MetricsProvider.');
  }
  return value;
}
