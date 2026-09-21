/**
 * The single value a `gauge`/`single-stat` mark renders. `DM-D22` requires
 * both marks to be queried with aggregation to exactly one value, so
 * `model.series` is expected to carry exactly one series by the time it
 * reaches here — enforced upstream (`markAggregation.ts`'s
 * `evaluateMarkAggregation`), not re-checked in this pure extraction.
 */
import type { ChartModel, ChartPoint } from '@contracts/metrics/types';

export interface MarkValue {
  point: ChartPoint;
  unit: string | null;
}

/**
 * Returns `null` when there is no series, or the aggregated series has no
 * rendered point (an all-gap range, or nothing selected yet) — the caller
 * renders a stated empty state, never a zero (same posture as obligation 1
 * for line rendering).
 */
export function resolveMarkValue(model: ChartModel): MarkValue | null {
  const series = model.series[0];
  if (series === undefined) {
    return null;
  }
  const point = series.points.at(-1);
  if (point === undefined) {
    return null;
  }
  return { point, unit: model.metric.unit };
}
