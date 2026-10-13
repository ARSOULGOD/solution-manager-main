import type { ReplicaObservationFixture } from '@shared/testdoubles/data/designer';

/**
 * The generation most replicas are on — ties broken by first-seen, same
 * as the reference's own `mode()` (`designer_panels.jsx`). Used only to
 * flag which replica is the odd one out visually; it is not a claim about
 * which generation is "correct" — `RolloutCard`'s own `diverged` prop is
 * the semantic signal for a halted, split rollout (task 062), which this
 * function has no way to distinguish from an in-progress one.
 */
export function majorityGeneration(
  replicas: readonly ReplicaObservationFixture[],
): string | undefined {
  const counts = new Map<string, number>();
  let best: string | undefined;
  let bestCount = 0;
  for (const replica of replicas) {
    const count = (counts.get(replica.generation) ?? 0) + 1;
    counts.set(replica.generation, count);
    if (count > bestCount) {
      bestCount = count;
      best = replica.generation;
    }
  }
  return best;
}

export function isOddGeneration(
  replica: ReplicaObservationFixture,
  replicas: readonly ReplicaObservationFixture[],
): boolean {
  const distinct = new Set(replicas.map((r) => r.generation)).size;
  return distinct > 1 && replica.generation !== majorityGeneration(replicas);
}
