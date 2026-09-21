import { describe, expect, it } from 'vitest';

import type { ReplicaObservationFixture } from '@shared/testdoubles/data/designer';

import { isOddGeneration, majorityGeneration } from './majorityGeneration';

function replica(id: string, generation: string): ReplicaObservationFixture {
  return { id, generation, restartObserved: true, health: 'GREEN' };
}

describe('majorityGeneration', () => {
  it('returns the only generation when every replica agrees', () => {
    const replicas = [replica('a', 'gen-1'), replica('b', 'gen-1')];
    expect(majorityGeneration(replicas)).toBe('gen-1');
  });

  it('returns the generation most replicas are on', () => {
    const replicas = [replica('a', 'gen-1'), replica('b', 'gen-1'), replica('c', 'gen-0')];
    expect(majorityGeneration(replicas)).toBe('gen-1');
  });

  it('breaks an even split by first-seen', () => {
    const replicas = [
      replica('a', 'gen-1'),
      replica('b', 'gen-1'),
      replica('c', 'gen-0'),
      replica('d', 'gen-0'),
    ];
    expect(majorityGeneration(replicas)).toBe('gen-1');
  });
});

describe('isOddGeneration', () => {
  it('is false for every replica when all generations agree', () => {
    const replicas = [replica('a', 'gen-1'), replica('b', 'gen-1')];
    expect(replicas.every((r) => !isOddGeneration(r, replicas))).toBe(true);
  });

  it('flags only the minority replica when one lags behind', () => {
    const replicas = [replica('a', 'gen-1'), replica('b', 'gen-1'), replica('c', 'gen-0')];
    expect(isOddGeneration(replicas[0]!, replicas)).toBe(false);
    expect(isOddGeneration(replicas[1]!, replicas)).toBe(false);
    expect(isOddGeneration(replicas[2]!, replicas)).toBe(true);
  });

  it('flags the minority side of an even split, never the majority', () => {
    const replicas = [
      replica('a', 'gen-1'),
      replica('b', 'gen-1'),
      replica('c', 'gen-0'),
      replica('d', 'gen-0'),
    ];
    expect(isOddGeneration(replicas[0]!, replicas)).toBe(false);
    expect(isOddGeneration(replicas[1]!, replicas)).toBe(false);
    expect(isOddGeneration(replicas[2]!, replicas)).toBe(true);
    expect(isOddGeneration(replicas[3]!, replicas)).toBe(true);
  });
});
