import { describe, expect, it } from 'vitest';

import { buildQuery } from './buildQuery';
import type { QueryCapabilities, WidgetQueryConfig } from './buildQuery';
import { canonicalQueryHash } from './canonicalHash';

const NOW = new Date('2026-08-14T12:00:00.000Z');
const fixedNow = (): Date => NOW;
const capabilities: QueryCapabilities = { features: { forceRaw: true } };

function query(config: WidgetQueryConfig) {
  return buildQuery(config, { range: { kind: 'relative', preset: '1h' }, resolution: 'auto' }, capabilities, fixedNow);
}

describe('canonicalQueryHash', () => {
  it('hashes equal for the same instances in a different order', () => {
    const a = query({
      metric: 'cache.evictions',
      selector: { entityType: 'cache-engine', instanceIds: ['a1b2', 'c3d4'] },
      seriesBy: 'instance',
      aggregation: { acrossReplicas: 'auto', acrossInstances: 'none' },
    });
    const b = query({
      metric: 'cache.evictions',
      selector: { entityType: 'cache-engine', instanceIds: ['c3d4', 'a1b2'] },
      seriesBy: 'instance',
      aggregation: { acrossReplicas: 'auto', acrossInstances: 'none' },
    });
    expect(canonicalQueryHash(a)).toBe(canonicalQueryHash(b));
  });

  it('hashes equal for the same label filter built with different key order', () => {
    const a = query({
      metric: 'cache.evictions',
      selector: {
        entityType: 'cache-engine',
        instanceIds: ['a1b2'],
        labels: { region: 'eu-west-1', az: '1a' },
      },
      seriesBy: 'instance',
      aggregation: { acrossReplicas: 'auto', acrossInstances: 'none' },
    });
    const b = query({
      metric: 'cache.evictions',
      selector: {
        entityType: 'cache-engine',
        instanceIds: ['a1b2'],
        labels: { az: '1a', region: 'eu-west-1' },
      },
      seriesBy: 'instance',
      aggregation: { acrossReplicas: 'auto', acrossInstances: 'none' },
    });
    expect(canonicalQueryHash(a)).toBe(canonicalQueryHash(b));
  });

  it('hashes equal for the same groupBy set built in a different order', () => {
    const base: WidgetQueryConfig = {
      metric: 'cache.evictions',
      selector: { entityType: 'cache-engine', instanceIds: ['a1b2'] },
      seriesBy: 'instance',
      aggregation: { acrossReplicas: 'auto', acrossInstances: 'sum' },
    };
    const a = query({ ...base, groupBy: ['region', 'az'] });
    const b = query({ ...base, groupBy: ['az', 'region'] });
    expect(canonicalQueryHash(a)).toBe(canonicalQueryHash(b));
  });

  it('hashes differently when the metric differs', () => {
    const base: WidgetQueryConfig = {
      metric: 'cache.evictions',
      selector: { entityType: 'cache-engine', instanceIds: ['a1b2'] },
      seriesBy: 'instance',
      aggregation: { acrossReplicas: 'auto', acrossInstances: 'none' },
    };
    const a = query(base);
    const b = query({ ...base, metric: 'cache.hit_rate' });
    expect(canonicalQueryHash(a)).not.toBe(canonicalQueryHash(b));
  });

  it('collapses a dashboard of 8 widgets over 4 metrics into 4 distinct hashes', () => {
    const metrics = ['cache.evictions', 'cache.hit_rate', 'cache.custom_lag', 'cache.miss_rate'];
    // Two widgets per metric, each built with a differently-ordered
    // instance selection — the same question asked twice per metric.
    const widgets = metrics.flatMap((metric) => [
      query({
        metric,
        selector: { entityType: 'cache-engine', instanceIds: ['a1b2', 'c3d4'] },
        seriesBy: 'instance',
        aggregation: { acrossReplicas: 'auto', acrossInstances: 'none' },
      }),
      query({
        metric,
        selector: { entityType: 'cache-engine', instanceIds: ['c3d4', 'a1b2'] },
        seriesBy: 'instance',
        aggregation: { acrossReplicas: 'auto', acrossInstances: 'none' },
      }),
    ]);
    expect(widgets).toHaveLength(8);
    const distinctHashes = new Set(widgets.map((w) => canonicalQueryHash(w)));
    expect(distinctHashes.size).toBe(4);
  });
});
