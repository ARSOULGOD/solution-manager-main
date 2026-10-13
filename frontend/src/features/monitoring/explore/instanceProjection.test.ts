import { describe, expect, it } from 'vitest';

import { projectSeriesCount, seriesCountFor, wouldExceedCap } from './instanceProjection';

const INSTANCES = [
  { id: 'a', replicaCount: 1 },
  { id: 'b', replicaCount: 3 },
  { id: 'c', replicaCount: 5 },
];

describe('seriesCountFor', () => {
  it('is always 1 under seriesBy: instance, regardless of replica count', () => {
    expect(seriesCountFor({ id: 'b', replicaCount: 3 }, 'instance')).toBe(1);
  });

  it('is the replica count under seriesBy: replica', () => {
    expect(seriesCountFor({ id: 'b', replicaCount: 3 }, 'replica')).toBe(3);
  });
});

describe('projectSeriesCount', () => {
  it('sums 1 per selected instance under seriesBy: instance', () => {
    expect(projectSeriesCount(INSTANCES, ['a', 'b', 'c'], 'instance')).toBe(3);
  });

  it('sums replicas per selected instance under seriesBy: replica', () => {
    expect(projectSeriesCount(INSTANCES, ['a', 'b', 'c'], 'replica')).toBe(1 + 3 + 5);
  });

  it('is 0 for an empty selection', () => {
    expect(projectSeriesCount(INSTANCES, [], 'instance')).toBe(0);
  });

  it('ignores an id not present in the instance list', () => {
    expect(projectSeriesCount(INSTANCES, ['not-a-real-id'], 'replica')).toBe(0);
  });

  it('switching to replica mode raises the same selection’s projection', () => {
    const selected = ['b', 'c'];
    const perInstance = projectSeriesCount(INSTANCES, selected, 'instance');
    const perReplica = projectSeriesCount(INSTANCES, selected, 'replica');
    expect(perReplica).toBeGreaterThan(perInstance);
  });
});

describe('wouldExceedCap', () => {
  it('is false when the candidate fits under the cap', () => {
    expect(wouldExceedCap(INSTANCES, ['a'], 'instance', 10, { id: 'b', replicaCount: 3 })).toBe(
      false,
    );
  });

  it('is true when the candidate would push the total past the cap', () => {
    // a + b already project 1 + 3 = 4; adding c (5 replicas) → 9 > 7.
    expect(wouldExceedCap(INSTANCES, ['a', 'b'], 'replica', 7, { id: 'c', replicaCount: 5 })).toBe(
      true,
    );
  });

  it('is false when the candidate lands exactly on the cap (cap is inclusive)', () => {
    expect(wouldExceedCap([], [], 'instance', 1, { id: 'a', replicaCount: 1 })).toBe(false);
  });

  it('is per-candidate, not a flat "anything unselected" gate: a small candidate can still fit while a large one would not', () => {
    // projected so far = 8 (replica mode); cap = 10.
    const selected = ['a', 'b']; // 1 + 3 = 4... use a cap that makes the boundary exact.
    const cap = 10;
    const smallCandidate = { id: 'small', replicaCount: 1 };
    const bigCandidate = { id: 'big', replicaCount: 8 };
    expect(wouldExceedCap(INSTANCES, selected, 'replica', cap, smallCandidate)).toBe(false);
    expect(wouldExceedCap(INSTANCES, selected, 'replica', cap, bigCandidate)).toBe(true);
  });
});
