/**
 * The one `QueryClient`'s defaults (task 029, `FE-D1`, `FE-D20`,
 * `dynamic-metrics` §10). `app/providers.tsx` constructs exactly one
 * instance of this at boot — features never construct their own.
 */
import { keepPreviousData, QueryClient } from '@tanstack/react-query';

import { shouldRetry } from './types';

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        // An admin/governance console re-focusing the tab is not a signal
        // to burn a request — data updates on its own refresh cadence.
        refetchOnWindowFocus: false,
        // G14 — the ONLY retry predicate in the app (task 028's shouldRetry:
        // 5xx only, never 501, capped at 3 attempts; 4xx incl. 422 never retried).
        retry: shouldRetry,
        // A range/filter change dims the current chart/table rather than
        // blanking it while the new request is in flight (`dynamic-metrics`
        // §10) — the stale data stays visible, just visually de-emphasised
        // by the consuming component's own `isPlaceholderData` check.
        placeholderData: keepPreviousData,
        structuralSharing: true,
      },
      mutations: {
        // `FE-D20`: a mutation is NEVER retried automatically and NEVER
        // optimistic. There is no onMutate/rollback pattern anywhere in
        // this app — apply/restart in particular terminate in
        // `ApplyOutcome.INDETERMINATE` (§6.0.4), which has no "succeeded"
        // variant to optimistically render before the server confirms it.
        retry: false,
      },
    },
  });
}
