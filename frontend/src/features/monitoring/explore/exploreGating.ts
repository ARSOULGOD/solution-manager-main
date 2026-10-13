/**
 * Pure step-lock rules for Explore's five-step control column (task 077,
 * Dec `DM-D9` `DM-D23`, `dynamic-metrics` §9.1/§11.1, UI §A.10). Kept out
 * of `ControlStep`/`ExplorePage` so the ordering rule itself — "metric
 * untyped locks 2-4; no instance aggregation locks 5" — is testable
 * without rendering anything, the same reasoning task 076's
 * `markAggregation.ts` gives for keeping the mark-aggregation refusal
 * rule out of `GaugeMark`/`SingleStatMark`.
 *
 * Returns reason *codes*, not translated copy — same split the visual
 * reference's own `ERR_TEXT` makes for its 422 codes (`explore.jsx`).
 * Codes are lower-kebab and deliberately distinct from that file's
 * SCREAMING_SNAKE 422 vocabulary (`METRIC_UNTYPED`, …): these three fire
 * before any query is issued, so they are a client-only gating concern,
 * not a server error being mirrored client-side.
 */
import type { MetricState } from '@shared/testdoubles/data/metrics';

import type { AcrossInstances } from '../metricsRepository';

export type ExploreLockReason =
  | 'metric-not-selected'
  | 'metric-needs-typing'
  | 'aggregation-not-set';

export type StepLock = { locked: false } | { locked: true; reason: ExploreLockReason };

export interface ExploreStepLocks {
  /** Step 1 is the entry point and is never locked (`DM-D9`). */
  metric: StepLock;
  instances: StepLock;
  seriesBy: StepLock;
  aggregation: StepLock;
  groupBy: StepLock;
}

export interface ExploreGatingInput {
  /** `null` — no metric chosen yet. Distinct from `needs-typing`: nothing has been picked at all. */
  metricState: MetricState | null;
  acrossInstances: AcrossInstances;
}

const UNLOCKED: StepLock = { locked: false };

export function evaluateExploreGating({
  metricState,
  acrossInstances,
}: ExploreGatingInput): ExploreStepLocks {
  const metricGate: StepLock =
    metricState === null
      ? { locked: true, reason: 'metric-not-selected' }
      : metricState === 'needs-typing'
        ? { locked: true, reason: 'metric-needs-typing' }
        : UNLOCKED;

  const groupByGate: StepLock =
    acrossInstances === 'none' ? { locked: true, reason: 'aggregation-not-set' } : UNLOCKED;

  return {
    metric: UNLOCKED,
    instances: metricGate,
    seriesBy: metricGate,
    aggregation: metricGate,
    groupBy: groupByGate,
  };
}
