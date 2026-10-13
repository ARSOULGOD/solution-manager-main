/**
 * Pure `DM-D17` rules for `DashboardToolbar`/`DashboardView` (task 085,
 * Dec `DM-D17`, `dynamic-metrics` §14). Refresh is `off · 10s · 30s · 1m ·
 * 5m`, **floored at the served bucket size** and **disabled for absolute
 * ranges** — "there is nothing new to fetch."
 *
 * **`estimatedBucketSeconds` is a client-side ESTIMATE, not a served
 * value.** No widget query runs from this task (`DashboardGrid`/
 * `WidgetFrame`, task 086, is what actually queries), so there is no real
 * `QueryResponse.resolution.bucketSeconds` to floor against yet — unlike
 * `ExploreResult.tsx`, which floors nothing because Explore has no
 * refresh control at all (`dynamic-metrics` §9.1: `RefreshControl` is
 * dashboard-only). The estimate reuses the SAME range-length cutoffs
 * architecture §8.4 states (`≤7d raw · 7–90d hourly · >90d daily`) against
 * the REAL, capabilities-advertised `minBucketSeconds` per tier — never a
 * hardcoded bucket literal — because every widget on one dashboard shares
 * one range (`DM-D21`), so the tier every widget resolves to is the same
 * one this estimate picks. Once 086 wires real per-widget queries, each
 * widget's own served `bucketSeconds` is authoritative over this estimate
 * for that widget; this only floors the ONE dashboard-wide control before
 * any of them has answered.
 */
import type { TimeRangeValue } from '../explore/timeRangeOptions';
import type { CapabilityTier } from '../metricsRepository';

export const REFRESH_INTERVAL_SECONDS = [0, 10, 30, 60, 300] as const;
export type RefreshIntervalSeconds = (typeof REFRESH_INTERVAL_SECONDS)[number];

export function isRefreshIntervalSeconds(value: number): value is RefreshIntervalSeconds {
  return (REFRESH_INTERVAL_SECONDS as readonly number[]).includes(value);
}

const DAY_MS = 24 * 60 * 60 * 1000;
// architecture.md §8.4 — fixed routing cutoffs, not tier-configurable.
const RAW_TIER_MAX_RANGE_MS = 7 * DAY_MS;
const HOURLY_TIER_MAX_RANGE_MS = 90 * DAY_MS;

function tierNameForRangeMs(rangeMs: number): 'raw' | 'hourly' | 'daily' {
  if (rangeMs <= RAW_TIER_MAX_RANGE_MS) return 'raw';
  if (rangeMs <= HOURLY_TIER_MAX_RANGE_MS) return 'hourly';
  return 'daily';
}

/** `undefined` when `tiers` (real or fail-closed) has no tier of that name — the caller floors nothing rather than guessing. */
export function estimatedBucketSeconds(
  rangeMs: number,
  tiers: readonly CapabilityTier[],
): number | undefined {
  const name = tierNameForRangeMs(rangeMs);
  return tiers.find((tier) => tier.name === name)?.minBucketSeconds;
}

/** `off` (0) always stays off — flooring never turns a disabled poll on. */
export function flooredRefreshSeconds(
  requested: RefreshIntervalSeconds,
  bucketSeconds: number | undefined,
): number {
  if (requested === 0 || bucketSeconds === undefined) {
    return requested;
  }
  return Math.max(requested, bucketSeconds);
}

/** A `custom` (absolute) range has nothing new to fetch — DM-D17. */
export function refreshDisabledForRange(timeRange: TimeRangeValue): boolean {
  return timeRange.kind === 'custom';
}
