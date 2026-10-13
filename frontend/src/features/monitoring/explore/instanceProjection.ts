/**
 * Pure series-count projection for the instance selector (task 078, Dec
 * `DM-D7` `DM-D23`, `dynamic-metrics` §11.4, Conflict C6). "The cap counts
 * rendered series, not instances": projection is `Σ replicas` under
 * `seriesBy: 'replica'` and `Σ 1` under `seriesBy: 'instance'`. Kept out of
 * `InstanceSelector` so the arithmetic is testable without rendering
 * anything — the same split `exploreGating.ts` (task 077) and
 * `markAggregation.ts` (task 076) already established for this feature.
 */
import type { SeriesBy } from '../metricsRepository';

export interface ProjectableInstance {
  id: string;
  /** This instance's replica count — the value `seriesBy: 'replica'` sums (`DM-D7`). */
  replicaCount: number;
}

/** RENDERED series one instance contributes, for the given `seriesBy`. */
export function seriesCountFor(instance: ProjectableInstance, seriesBy: SeriesBy): number {
  return seriesBy === 'replica' ? instance.replicaCount : 1;
}

/** Total rendered series across every SELECTED instance. */
export function projectSeriesCount(
  instances: readonly ProjectableInstance[],
  selectedIds: readonly string[],
  seriesBy: SeriesBy,
): number {
  const selected = new Set(selectedIds);
  return instances
    .filter((instance) => selected.has(instance.id))
    .reduce((total, instance) => total + seriesCountFor(instance, seriesBy), 0);
}

/**
 * Whether selecting `candidate` (currently unselected) would push the
 * projected total past `cap`. Per-instance, not the reference's flat
 * "disable everything once at cap" (`explore.jsx`'s `atCap` gate) — a
 * deliberate correction: with instances of differing replica counts, a
 * flat gate lets a large instance be selected right up to the boundary and
 * then fail at query time, which the task's own acceptance box forbids
 * ("must not let a selection succeed and then fail at query time").
 */
export function wouldExceedCap(
  instances: readonly ProjectableInstance[],
  selectedIds: readonly string[],
  seriesBy: SeriesBy,
  cap: number,
  candidate: ProjectableInstance,
): boolean {
  const projected = projectSeriesCount(instances, selectedIds, seriesBy);
  return projected + seriesCountFor(candidate, seriesBy) > cap;
}
