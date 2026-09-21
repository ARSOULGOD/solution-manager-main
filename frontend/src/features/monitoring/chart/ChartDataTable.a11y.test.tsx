import { axe } from 'jest-axe';
import { describe, expect, it, vi } from 'vitest';

import type { ChartModel } from '@contracts/metrics/types';

import { renderWithProviders } from '../../../test/renderWithProviders';

import { ChartDataTable } from './ChartDataTable';

const modes = ['light', 'dark'] as const;

function model(): ChartModel {
  return {
    metric: { name: 'cache.evictions', metricType: 'counter', unit: 'count' },
    render: { transform: 'rate' },
    coverage: { requestedFrom: '2026-08-30T00:00:00Z', servedFrom: '2026-08-30T00:00:00Z', truncatedByRetention: false },
    series: [
      {
        seriesRef: { instanceId: 'cache-01', placementId: null, sourceKey: null },
        label: 'cache-01',
        state: 'inactive-replica',
        replicaComposition: { total: 1, inactive: 1 },
        color: 'steelblue',
        dashPattern: 'solid',
        points: [
          { ts: '2026-08-30T00:00:00Z', value: 1, lowCoverage: false },
          { ts: '2026-08-30T00:01:00Z', value: 2, lowCoverage: true },
        ],
        gaps: [],
      },
    ],
  };
}

describe('ChartDataTable a11y', () => {
  it.each(modes)('has no violations open, in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <ChartDataTable model={model()} open onClose={vi.fn()} title="Evictions" />,
      { mode },
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
