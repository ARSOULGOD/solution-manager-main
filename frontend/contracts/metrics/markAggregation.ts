/**
 * `DM-D22` — `single-stat` and `gauge` render exactly one number. With more
 * than one instance selected, `acrossInstances: 'none'` is refused
 * (`422 STAT_REQUIRES_AGGREGATION`) rather than silently showing the first
 * instance's value under a title naming several (`dynamic-metrics` §5.8,
 * §7, decision rationale). This is a pure mirror of that server rule —
 * `contracts/` imports nothing app-side (task 046 acceptance) — so a
 * control (task 080's `AggregationControl`) or the Explore error list
 * (task 082) can state the refusal before a query is even issued, not just
 * react to the response.
 *
 * A single instance with `acrossInstances: 'none'` is already one value and
 * is **not** refused — the decision's own wording conditions refusal on
 * "more than one instance selected".
 */
import type { AcrossInstances } from './buildQuery';
import type { Mark } from './types';

/** Closed set (`SM-D31`, `DM-D12`) — enumerated once so "three marks only" is checkable, not just typed. */
export const MARKS: readonly Mark[] = ['line', 'gauge', 'single-stat'];

/** `line` never requires aggregation; both single-value marks always do (`DM-D22`). */
export function marksRequiringAggregation(mark: Mark): boolean {
  return mark === 'gauge' || mark === 'single-stat';
}

export interface MarkAggregationCheck {
  mark: Mark;
  instanceCount: number;
  acrossInstances: AcrossInstances;
}

export type MarkAggregationResult =
  | { refused: false }
  | { refused: true; code: 'STAT_REQUIRES_AGGREGATION' };

export function evaluateMarkAggregation({
  mark,
  instanceCount,
  acrossInstances,
}: MarkAggregationCheck): MarkAggregationResult {
  if (marksRequiringAggregation(mark) && instanceCount > 1 && acrossInstances === 'none') {
    return { refused: true, code: 'STAT_REQUIRES_AGGREGATION' };
  }
  return { refused: false };
}
