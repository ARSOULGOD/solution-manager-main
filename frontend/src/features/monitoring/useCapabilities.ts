/**
 * `useCapabilities` (task 068, `DM-D10`, `dynamic-metrics` §5.4/§17). The single
 * seam through which every metrics-consuming component learns what the server
 * currently advertises — tiers, caps, marks, aggregations, features. No caller
 * may hardcode a preset, cap or tier; they read this hook's result instead.
 *
 * The app has no code path that queries metrics without a capabilities read, so
 * a component blocked on `capabilities: undefined` while the fetch is in flight
 * or failed is a component that silently does nothing. This hook never returns
 * `undefined`: `FAIL_CLOSED_CAPABILITIES` (`capabilityDefaults.ts`) stands in
 * both while loading and once the fetch has failed, and `degraded` tells the
 * caller which case it is so it can render §17's banner.
 */
import { useQuery } from '@tanstack/react-query';

import { queryKeys } from '@shared/api/queryKeys';

import { FAIL_CLOSED_CAPABILITIES } from './capabilityDefaults';
import type { Capabilities, MetricsRepository } from './metricsRepository';

export interface UseCapabilitiesResult {
  capabilities: Capabilities;
  /** True once the fetch has failed (queryClient's retry policy exhausted). */
  degraded: boolean;
  isLoading: boolean;
}

export function useCapabilities(repository: MetricsRepository): UseCapabilitiesResult {
  const query = useQuery({
    queryKey: queryKeys.metrics.capabilities(),
    queryFn: ({ signal }) => repository.capabilities(signal),
  });

  return {
    capabilities: query.data ?? FAIL_CLOSED_CAPABILITIES,
    degraded: query.isError,
    isLoading: query.isLoading,
  };
}
