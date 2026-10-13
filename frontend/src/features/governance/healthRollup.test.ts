import { describe, expect, it } from 'vitest';

import type { HealthState } from './governanceRepository';
import { deriveOverallState, HEALTH_STATE_VOCAB } from './healthRollup';

const ALL_STATES: readonly HealthState[] = [
  'GREEN',
  'RED',
  'RESTART_EXPECTED',
  'INACTIVE_REPLICA',
  'NOT_APPLICABLE',
  'unreachable',
  'not_exposed',
  'missing',
  'bootstrap',
];

describe('HEALTH_STATE_VOCAB', () => {
  it('maps every one of the nine wire states to a vocab entry', () => {
    for (const state of ALL_STATES) {
      expect(HEALTH_STATE_VOCAB[state]).toBeDefined();
    }
  });

  it('never maps NOT_APPLICABLE to the error tone', () => {
    expect(HEALTH_STATE_VOCAB.NOT_APPLICABLE.tone).not.toBe('err');
  });

  it('maps RED to the error tone', () => {
    expect(HEALTH_STATE_VOCAB.RED.tone).toBe('err');
  });

  it('gives unreachable a distinct entry from RED, not a fallback to it', () => {
    expect(HEALTH_STATE_VOCAB.unreachable).not.toBe(HEALTH_STATE_VOCAB.RED);
    expect(HEALTH_STATE_VOCAB.unreachable.label).toBe('Unreachable');
    expect(HEALTH_STATE_VOCAB.unreachable.tone).toBe('err');
  });
});

describe('deriveOverallState', () => {
  it('defaults to NOT_APPLICABLE when there are no placements', () => {
    expect(deriveOverallState([])).toBe('NOT_APPLICABLE');
  });

  it('prefers unreachable over RED', () => {
    expect(deriveOverallState(['RED', 'unreachable'])).toBe('unreachable');
  });

  it('prefers RED over RESTART_EXPECTED', () => {
    expect(deriveOverallState(['RESTART_EXPECTED', 'RED'])).toBe('RED');
  });

  it('is GREEN only when every placement is GREEN', () => {
    expect(deriveOverallState(['GREEN', 'GREEN'])).toBe('GREEN');
  });
});
