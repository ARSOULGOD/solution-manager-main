import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import type { CatalogEntry } from '@shared/testdoubles/data/metrics';

import { renderWithProviders } from '../../../test/renderWithProviders';
import type { MetricsRepository } from '../metricsRepository';

import { MetricSelector } from './MetricSelector';

const CATALOG: readonly CatalogEntry[] = [
  {
    name: 'cache.hit_rate',
    metricType: 'gauge',
    unit: 'ratio',
    layer: 'package-baseline',
    state: 'plottable',
    lastSeen: 0,
    labelKeys: [],
  },
  {
    name: 'cache.custom_lag',
    metricType: null,
    unit: null,
    layer: 'user-extension',
    state: 'needs-typing',
    lastSeen: 0,
    labelKeys: [],
  },
  {
    name: 'cache.queue_depth',
    metricType: 'gauge',
    unit: 'items',
    layer: 'user-extension',
    state: 'type-suspect',
    lastSeen: 0,
    labelKeys: [],
    suspectReason: 'Observed values climb then drop to zero like a counter reset.',
  },
];

const metricsRepository: Pick<MetricsRepository, 'declareMetricType'> = {
  declareMetricType: () => Promise.reject(new Error('not exercised in a11y assertions')),
};

const modes = ['light', 'dark'] as const;

describe('MetricSelector a11y', () => {
  it.each(modes)('has no violations with nothing selected, in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <MetricSelector
        catalog={CATALOG}
        selected={null}
        onSelectedChange={() => {}}
        metricsRepository={metricsRepository}
      />,
      { mode },
    );
    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('has no violations with a type-suspect metric selected, in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <MetricSelector
        catalog={CATALOG}
        selected="cache.queue_depth"
        onSelectedChange={() => {}}
        metricsRepository={metricsRepository}
      />,
      { mode },
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
