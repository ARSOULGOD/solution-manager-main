/**
 * `canonicalQueryHash()` — task 070, `dynamic-metrics` §10's cache key:
 * "key order normalized, instance IDs sorted, relative ranges resolved to
 * absolute at issue time" (the range resolution happens in `buildQuery`;
 * this module only normalizes what it is handed). Two widgets asking the
 * identical question must produce the identical string, so TanStack
 * Query's `queryKeys.metrics.query(queryHash)` (`src/shared/api/
 * queryKeys.ts`) collapses them to one cache entry and one in-flight
 * request instead of two.
 *
 * The "hash" is a canonical JSON string, not a checksum — the canonical
 * string itself is order-insensitive and short enough to use directly as
 * a cache key, so compressing it further would only add a collision risk
 * for no benefit.
 */
import type { QueryRequest } from './buildQuery';

function stableStringify(value: unknown): string {
  return JSON.stringify(value, (_key, val: unknown) => {
    if (val !== null && typeof val === 'object' && !Array.isArray(val)) {
      const obj = val as Record<string, unknown>;
      const sorted: Record<string, unknown> = {};
      for (const key of Object.keys(obj).sort()) {
        sorted[key] = obj[key];
      }
      return sorted;
    }
    return val;
  });
}

/**
 * `instanceIds` and `groupBy` are sets, not sequences — the API never
 * assigns meaning to their order (§5.6, §5.7) — so two configs differing
 * only in selection order must hash equal. Object key order is handled
 * generically by `stableStringify`; these two arrays are the only fields
 * that need an explicit, semantic (value) sort first.
 */
export function canonicalQueryHash(query: QueryRequest): string {
  const canonical: QueryRequest = {
    ...query,
    selector: {
      ...query.selector,
      instanceIds: [...query.selector.instanceIds].sort(),
    },
  };
  const withCanonicalGroupBy: QueryRequest =
    query.groupBy !== undefined ? { ...canonical, groupBy: [...query.groupBy].sort() } : canonical;
  return stableStringify(withCanonicalGroupBy);
}
