import { describe, expect, it } from 'vitest';

import { healthVocab } from '@shared/vocab';

import type { HealthRollupState } from './governanceRepository';
import { ROLLUP_TO_HEALTH_KEY } from './healthRollup';

const ALL_ROLLUPS: readonly HealthRollupState[] = [
  'GREEN',
  'RED',
  'RESTART_EXPECTED',
  'INACTIVE_REPLICA',
  'NOT_APPLICABLE',
];

describe('ROLLUP_TO_HEALTH_KEY', () => {
  it('maps every rollup state to a healthVocab entry', () => {
    for (const rollup of ALL_ROLLUPS) {
      const key = ROLLUP_TO_HEALTH_KEY[rollup];
      expect(healthVocab[key]).toBeDefined();
    }
  });

  it('never maps NOT_APPLICABLE to an error-tone entry', () => {
    expect(healthVocab[ROLLUP_TO_HEALTH_KEY.NOT_APPLICABLE].tone).not.toBe('err');
  });

  it('maps RED to the error-tone entry', () => {
    expect(healthVocab[ROLLUP_TO_HEALTH_KEY.RED].tone).toBe('err');
  });
});
