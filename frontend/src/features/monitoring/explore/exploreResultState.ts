/**
 * Pure result-column state derivation for Explore (task 082, Dec `DM-D16`
 * `FE-D10` `FE-D20` `SM-D27`, `dynamic-metrics` §5.8/§11.2, UI §A.10 —
 * mirrors `docs/design/frontend/reference/app/explore.jsx`'s own
 * `blocking`/`result` branching). Kept out of `ExploreResult.tsx` so the
 * branching itself — which of "empty selection", "loading", "error" or
 * "result" applies — is testable without rendering anything, the same
 * split `exploreGating.ts` (task 077) already established for this
 * feature's control column.
 *
 * `hasMetric`/`hasInstances` are checked BEFORE the query status: a query
 * disabled by empty selection sits at TanStack's permanent `'pending'`
 * `fetchStatus: 'idle'`, which must never be confused with a real
 * in-flight first load.
 *
 * `'success'` with `isPlaceholderData: true` is TanStack's own signal for
 * "showing the previous key's data while the new key's request is still in
 * flight" (`placeholderData: keepPreviousData`, `queryClient.ts`) — exactly
 * `dynamic-metrics` §11.2's "Refreshing" row, and exactly why that row's
 * `response` here is the STALE previous response, not the new one.
 */
import type { QueryResponse } from '../metricsRepository';

export type ExploreResultMissingSelection = 'metric' | 'instances';

export type ExploreResultState =
  | { kind: 'empty-selection'; missing: ExploreResultMissingSelection }
  | { kind: 'loading' }
  | { kind: 'error'; error: unknown }
  | { kind: 'result'; response: QueryResponse; refreshing: boolean };

export interface DeriveExploreResultStateInput {
  hasMetric: boolean;
  hasInstances: boolean;
  /** TanStack `UseQueryResult.status` — `'pending'` covers both "disabled" and "first fetch in flight". */
  status: 'pending' | 'error' | 'success';
  isPlaceholderData: boolean;
  error: unknown;
  data: QueryResponse | undefined;
}

export function deriveExploreResultState(input: DeriveExploreResultStateInput): ExploreResultState {
  if (!input.hasMetric) {
    return { kind: 'empty-selection', missing: 'metric' };
  }
  if (!input.hasInstances) {
    return { kind: 'empty-selection', missing: 'instances' };
  }
  if (input.status === 'error') {
    return { kind: 'error', error: input.error };
  }
  if (input.status === 'pending') {
    return { kind: 'loading' };
  }
  // `status === 'success'` — `data` is always present here; TanStack's own
  // types leave it optional only to cover the `'pending'` branch above.
  return { kind: 'result', response: input.data as QueryResponse, refreshing: input.isPlaceholderData };
}

/** `dynamic-metrics` §11.2's "No data for range": zero points across every series, whatever the reason. */
export function hasNoDataInRange(response: QueryResponse): boolean {
  return response.series.every((series) => series.points.length === 0);
}
