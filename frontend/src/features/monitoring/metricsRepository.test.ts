import { describe, expect, it } from 'vitest';

import { createConsoleClient } from '@shared/api';

import { createMetricsRepository } from './metricsRepository';

const consoleClient = createConsoleClient('/api');

describe('createMetricsRepository (fixture-backed, SPECIFIED per dynamic-metrics §5.4-5.8)', () => {
  it('resolves capabilities, the catalog, and a range query', async () => {
    const repo = createMetricsRepository(consoleClient);

    const capabilities = await repo.capabilities();
    expect(capabilities.maxSeriesPerWidget).toBeGreaterThan(0);
    expect(capabilities.tiers.length).toBeGreaterThan(0);
    expect(capabilities.features.forceRaw).toBe(true);

    const catalog = await repo.metricCatalog({});
    expect(catalog).toHaveLength(8);

    const response = await repo.queryRange({
      queryVersion: 1,
      metric: 'cache.hit_rate',
      selector: { entityType: 'cache-instance', instanceIds: ['cache-prod-01'], labels: {} },
      range: { from: '2026-08-14T00:00:00Z', to: '2026-08-14T10:00:00Z' },
      resolution: 'auto',
      seriesBy: 'instance',
      aggregation: { acrossReplicas: 'auto', acrossInstances: 'none' },
    });

    expect(response.queryVersion).toBe(1);
    expect(response.series.length).toBeGreaterThan(0);
  });

  it('exportRangeUrl() never issues a request — same-origin URL only, never buffered (task 035 dependency)', () => {
    const repo = createMetricsRepository(consoleClient);

    const url = repo.exportRangeUrl({
      queryVersion: 1,
      metric: 'cache.hit_rate',
      selector: { entityType: 'cache-instance', instanceIds: [], labels: {} },
      range: { from: '2026-08-14T00:00:00Z', to: '2026-08-14T10:00:00Z' },
      resolution: 'auto',
      seriesBy: 'instance',
      aggregation: { acrossReplicas: 'auto', acrossInstances: 'none' },
    });

    expect(url).toMatch(/^\/api\/console\/v1\/export\/range\?/);
  });
});
