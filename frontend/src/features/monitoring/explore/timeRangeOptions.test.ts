import { describe, expect, it } from 'vitest';

import type { CapabilityTier } from '../metricsRepository';

import { permittedPresets, presetDurationMs, resolveTimeRange } from './timeRangeOptions';

describe('permittedPresets', () => {
  it('caps at 24h for the fail-closed single-day tier (§17)', () => {
    const tiers: CapabilityTier[] = [{ name: 'raw', retention: 'P1D', minBucketSeconds: 300 }];
    expect(permittedPresets(tiers)).toEqual(['15m', '1h', '6h', '24h']);
  });

  it('reaches 6mo when a tier retention covers it (REQ-007)', () => {
    const tiers: CapabilityTier[] = [
      { name: 'raw', retention: 'P6M', minBucketSeconds: 15 },
      { name: 'hourly', retention: 'P2Y', minBucketSeconds: 3600 },
      { name: 'daily', retention: null, minBucketSeconds: 86400 },
    ];
    expect(permittedPresets(tiers)).toEqual(['15m', '1h', '6h', '24h', '7d', '30d', '90d', '6mo']);
  });

  it('takes the longest tier, not the first, when tiers are out of order', () => {
    const tiers: CapabilityTier[] = [
      { name: 'daily', retention: 'P90D', minBucketSeconds: 86400 },
      { name: 'raw', retention: 'P1D', minBucketSeconds: 15 },
    ];
    expect(permittedPresets(tiers)).toEqual(['15m', '1h', '6h', '24h', '7d', '30d', '90d']);
  });

  it('offers nothing when tiers is empty', () => {
    expect(permittedPresets([])).toEqual([]);
  });
});

describe('resolveTimeRange', () => {
  it('resolves a preset to [now - duration, now]', () => {
    const now = new Date('2026-08-14T10:00:00Z');
    const result = resolveTimeRange({ kind: 'preset', preset: '1h' }, now);
    expect(result).toEqual({ from: '2026-08-14T09:00:00.000Z', to: '2026-08-14T10:00:00.000Z' });
  });

  it('passes a custom range through unchanged', () => {
    const result = resolveTimeRange({
      kind: 'custom',
      from: '2026-08-01T00:00:00.000Z',
      to: '2026-08-07T00:00:00.000Z',
    });
    expect(result).toEqual({ from: '2026-08-01T00:00:00.000Z', to: '2026-08-07T00:00:00.000Z' });
  });
});

describe('presetDurationMs', () => {
  it('is monotonically increasing across the preset order', () => {
    const durations = (['15m', '1h', '6h', '24h', '7d', '30d', '90d', '6mo'] as const).map(
      presetDurationMs,
    );
    for (let i = 1; i < durations.length; i += 1) {
      expect(durations[i]).toBeGreaterThan(durations[i - 1] as number);
    }
  });
});
