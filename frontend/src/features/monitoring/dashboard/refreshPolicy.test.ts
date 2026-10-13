import { describe, expect, it } from 'vitest';

import type { CapabilityTier } from '../metricsRepository';

import {
  estimatedBucketSeconds,
  flooredRefreshSeconds,
  isRefreshIntervalSeconds,
  refreshDisabledForRange,
  REFRESH_INTERVAL_SECONDS,
} from './refreshPolicy';

const DAY_MS = 24 * 60 * 60 * 1000;

// Real fixture tier shape (`console.ts`'s `/capabilities` handler) — raw/hourly/daily, not invented here.
const TIERS: readonly CapabilityTier[] = [
  { name: 'raw', retention: 'P6M', minBucketSeconds: 15 },
  { name: 'hourly', retention: 'P2Y', minBucketSeconds: 3600 },
  { name: 'daily', retention: null, minBucketSeconds: 86400 },
];

describe('estimatedBucketSeconds', () => {
  it('picks raw at and under the 7-day cutoff', () => {
    expect(estimatedBucketSeconds(7 * DAY_MS, TIERS)).toBe(15);
    expect(estimatedBucketSeconds(DAY_MS, TIERS)).toBe(15);
  });

  it('picks hourly between 7 and 90 days', () => {
    expect(estimatedBucketSeconds(7 * DAY_MS + 1, TIERS)).toBe(3600);
    expect(estimatedBucketSeconds(90 * DAY_MS, TIERS)).toBe(3600);
  });

  it('picks daily beyond 90 days', () => {
    expect(estimatedBucketSeconds(90 * DAY_MS + 1, TIERS)).toBe(86400);
    expect(estimatedBucketSeconds(182 * DAY_MS, TIERS)).toBe(86400);
  });

  it('returns undefined when no tier of that name is advertised (fail-closed single-tier)', () => {
    const failClosed: readonly CapabilityTier[] = [{ name: 'raw', retention: 'P1D', minBucketSeconds: 300 }];
    expect(estimatedBucketSeconds(90 * DAY_MS + 1, failClosed)).toBeUndefined();
  });
});

describe('flooredRefreshSeconds', () => {
  it('floors a requested interval up to the bucket size', () => {
    expect(flooredRefreshSeconds(10, 3600)).toBe(3600);
  });

  it('leaves a requested interval already at or above the bucket size alone', () => {
    expect(flooredRefreshSeconds(300, 60)).toBe(300);
  });

  it('leaves "off" (0) off regardless of bucket size', () => {
    expect(flooredRefreshSeconds(0, 15)).toBe(0);
  });

  it('passes the request through unfloored when no bucket estimate exists', () => {
    expect(flooredRefreshSeconds(10, undefined)).toBe(10);
  });
});

describe('refreshDisabledForRange', () => {
  it('is disabled for a custom (absolute) range', () => {
    expect(refreshDisabledForRange({ kind: 'custom', from: '2026-01-01T00:00:00Z', to: '2026-01-02T00:00:00Z' })).toBe(
      true,
    );
  });

  it('is enabled for a preset (relative) range', () => {
    expect(refreshDisabledForRange({ kind: 'preset', preset: '24h' })).toBe(false);
  });
});

describe('isRefreshIntervalSeconds', () => {
  it('accepts every member of REFRESH_INTERVAL_SECONDS', () => {
    for (const value of REFRESH_INTERVAL_SECONDS) {
      expect(isRefreshIntervalSeconds(value)).toBe(true);
    }
  });

  it('rejects a value outside the closed set (e.g. an arbitrary doc-declared interval)', () => {
    expect(isRefreshIntervalSeconds(45)).toBe(false);
  });
});
