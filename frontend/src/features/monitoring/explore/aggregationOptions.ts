/**
 * Pure `DM-D8` rule: which `acrossInstances` functions a metric's type
 * permits, intersected with what the deployment actually advertises
 * (`capabilities.aggregations`) — never a hardcoded list, the same
 * "derived from capabilities" discipline the time-range presets hold.
 * Kept out of `AggregationControl` for the same reason `exploreGating.ts`
 * and `instanceProjection.ts` split their own rules out: testable without
 * rendering anything.
 *
 * Feature-scoped rather than `contracts/metrics/`, unlike sibling
 * `markAggregation.ts` (task 076): this rule needs live
 * `capabilities.aggregations`, an app-side deployment concern, not a
 * portable mirror of one server refusal code that a future Java
 * implementation would also read.
 */
import type { MetricType } from '@shared/testdoubles/data/metrics';

import type { AcrossInstances } from '../metricsRepository';

export type AggregationFunction = Exclude<AcrossInstances, 'none'>;

/**
 * `DM-D8`'s table. `gauge`/`counter` permit the same four functions (the
 * server computes each with a different type-aware formula — not this
 * module's concern); `histogram` is unsupported in v1 and offers nothing.
 */
const TYPE_PERMITTED: Record<MetricType, readonly AggregationFunction[]> = {
  gauge: ['sum', 'avg', 'min', 'max'],
  counter: ['sum', 'avg', 'min', 'max'],
  histogram: [],
};

/**
 * `metricType: null` (no declared type) returns `[]` — unreachable in
 * practice since `exploreGating.ts` locks this step before a `null` type
 * can reach it, but the function stays total rather than assuming its
 * caller. Canonical order (`sum, avg, min, max`) is always preserved,
 * regardless of the input capability array's own order.
 */
export function permittedAggregationFunctions(
  metricType: MetricType | null,
  capabilityAggregations: readonly AggregationFunction[],
): readonly AggregationFunction[] {
  const typePermitted = metricType === null ? [] : TYPE_PERMITTED[metricType];
  return typePermitted.filter((fn) => capabilityAggregations.includes(fn));
}
