import { describe, expect, it } from 'vitest';

import { CACHE_INSTANCES } from './instances';
import { generateQueryResponse, METRIC_CATALOG, type SeriesScenario } from './metrics';

const RANGE = { from: Date.parse('2026-08-14T00:00:00Z'), to: Date.parse('2026-08-14T10:00:00Z') };
const HEALTHY_INSTANCES = CACHE_INSTANCES.filter((i) => i.id === 'cache-prod-01');

describe('METRIC_CATALOG', () => {
  it('has eight entries', () => {
    expect(METRIC_CATALOG).toHaveLength(8);
  });

  it('has exactly one needs-typing and one type-suspect entry', () => {
    expect(METRIC_CATALOG.filter((m) => m.state === 'needs-typing')).toHaveLength(1);
    expect(METRIC_CATALOG.filter((m) => m.state === 'type-suspect')).toHaveLength(1);
  });

  it('the needs-typing entry has no type or unit', () => {
    const entry = METRIC_CATALOG.find((m) => m.state === 'needs-typing');
    expect(entry?.metricType).toBeNull();
    expect(entry?.unit).toBeNull();
  });

  it('the type-suspect entry states its reason', () => {
    const entry = METRIC_CATALOG.find((m) => m.state === 'type-suspect');
    expect(entry?.suspectReason).toBeTruthy();
  });

  it('spans both layers', () => {
    const layers = new Set(METRIC_CATALOG.map((m) => m.layer));
    expect(layers.has('package-baseline')).toBe(true);
    expect(layers.has('user-extension')).toBe(true);
  });
});

describe('generateQueryResponse — deterministic', () => {
  it('is deterministic — same inputs, same output', () => {
    const a = generateQueryResponse({
      metricName: 'cache.hit_rate',
      instances: HEALTHY_INSTANCES,
      seriesBy: 'instance',
      scenario: 'normal',
      range: RANGE,
    });
    const b = generateQueryResponse({
      metricName: 'cache.hit_rate',
      instances: HEALTHY_INSTANCES,
      seriesBy: 'instance',
      scenario: 'normal',
      range: RANGE,
    });
    expect(a).toEqual(b);
  });
});

describe('generateQueryResponse — the eight required states (§A.13.4)', () => {
  const SCENARIOS: SeriesScenario[] = [
    'normal',
    'gaps',
    'lowcov',
    'retention',
    'nodata',
    'partial',
    'inactive',
    'pending',
  ];

  it.each(SCENARIOS)('produces a response for scenario "%s"', (scenario) => {
    const result = generateQueryResponse({
      metricName: 'cache.hit_rate',
      instances: HEALTHY_INSTANCES,
      seriesBy: 'instance',
      scenario,
      range: RANGE,
    });
    expect(result.series.length).toBeGreaterThan(0);
  });

  it('"gaps" leaves a hole — a bucket with no samples emits NO tuple (G10)', () => {
    const result = generateQueryResponse({
      metricName: 'cache.hit_rate',
      instances: HEALTHY_INSTANCES,
      seriesBy: 'instance',
      scenario: 'gaps',
      range: RANGE,
    });
    expect(result.series[0]?.gaps.length).toBeGreaterThan(0);
    const totalPossibleBuckets = Math.floor((RANGE.to - RANGE.from) / 3_600_000) + 1;
    expect(result.series[0]?.points.length).toBeLessThan(totalPossibleBuckets);
  });

  it('"lowcov" produces at least one point with sampleCount < expectedCount', () => {
    const result = generateQueryResponse({
      metricName: 'cache.hit_rate',
      instances: HEALTHY_INSTANCES,
      seriesBy: 'instance',
      scenario: 'lowcov',
      range: RANGE,
    });
    const lowCoveragePoints = result.series[0]?.points.filter(
      ([, , sample, expected]) => sample < expected,
    );
    expect(lowCoveragePoints?.length).toBeGreaterThan(0);
  });

  it('"retention" truncates coverage and marks it', () => {
    const result = generateQueryResponse({
      metricName: 'cache.hit_rate',
      instances: HEALTHY_INSTANCES,
      seriesBy: 'instance',
      scenario: 'retention',
      range: RANGE,
    });
    expect(result.coverage.truncatedByRetention).toBe(true);
    expect(result.coverage.servedFrom).toBeGreaterThan(RANGE.from);
  });

  it('"nodata" produces series with zero points — never a fabricated zero line', () => {
    const result = generateQueryResponse({
      metricName: 'cache.hit_rate',
      instances: HEALTHY_INSTANCES,
      seriesBy: 'instance',
      scenario: 'nodata',
      range: RANGE,
    });
    expect(result.series[0]?.points).toHaveLength(0);
  });

  it('"partial" sets degraded and names the unavailable instance', () => {
    const result = generateQueryResponse({
      metricName: 'cache.hit_rate',
      instances: HEALTHY_INSTANCES,
      seriesBy: 'instance',
      scenario: 'partial',
      range: RANGE,
    });
    expect(result.partial.degraded).toBe(true);
    expect(result.partial.unavailable.length).toBeGreaterThan(0);
  });

  it('"inactive" replica series read legitimately zero, not a gap', () => {
    const instanceWithStandby = CACHE_INSTANCES.find((i) => i.id === 'cache-prod-01')!;
    const result = generateQueryResponse({
      metricName: 'cache.hit_rate',
      instances: [instanceWithStandby],
      seriesBy: 'replica',
      scenario: 'normal',
      range: RANGE,
    });
    const standby = result.series.find((s) => s.state === 'inactive-replica');
    expect(standby).toBeDefined();
    expect(standby?.points.every(([, value]) => value === 0)).toBe(true);
  });

  it('"pending" marks every series pending-config-change', () => {
    const result = generateQueryResponse({
      metricName: 'cache.hit_rate',
      instances: HEALTHY_INSTANCES,
      seriesBy: 'instance',
      scenario: 'pending',
      range: RANGE,
    });
    expect(result.series.every((s) => s.state === 'pending-config-change')).toBe(true);
  });
});
