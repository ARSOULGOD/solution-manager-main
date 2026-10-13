import { describe, expect, it } from 'vitest';

import { FAIL_CLOSED_CAPABILITIES } from './capabilityDefaults';

describe('FAIL_CLOSED_CAPABILITIES (`dynamic-metrics` §17)', () => {
  it('caps the series count at 10', () => {
    expect(FAIL_CLOSED_CAPABILITIES.maxSeriesPerWidget).toBe(10);
  });

  it('advertises no tier reaching past 24h, so no preset beyond it can be derived', () => {
    for (const tier of FAIL_CLOSED_CAPABILITIES.tiers) {
      expect(tier.retention).toBe('P1D');
    }
    expect(FAIL_CLOSED_CAPABILITIES.maxRange).toBe('P1D');
  });

  it('never advertises forced-Raw', () => {
    expect(FAIL_CLOSED_CAPABILITIES.features.forceRaw).toBe(false);
  });

  it('keeps every tier internally consistent — its bucket count never exceeds maxPointsPerSeries', () => {
    const dayInSeconds = 24 * 60 * 60;
    for (const tier of FAIL_CLOSED_CAPABILITIES.tiers) {
      const bucketCount = dayInSeconds / tier.minBucketSeconds;
      expect(bucketCount).toBeLessThanOrEqual(FAIL_CLOSED_CAPABILITIES.maxPointsPerSeries);
    }
  });
});
