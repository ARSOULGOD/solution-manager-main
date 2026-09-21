import { axe } from 'jest-axe';
import { describe, expect, it, vi } from 'vitest';

import type { ChartModel } from '@contracts/metrics/types';

import { renderWithProviders } from '../../../test/renderWithProviders';

import { ChartRenderer } from './ChartRenderer';

const modes = ['light', 'dark'] as const;

vi.mock('echarts', () => ({
  init: () => ({ setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn() }),
}));

function model(): ChartModel {
  return {
    metric: { name: 'cache.evictions', metricType: 'counter', unit: 'count' },
    render: { transform: 'rate' },
    coverage: { requestedFrom: '2026-08-30T00:00:00Z', servedFrom: '2026-08-30T00:00:00Z', truncatedByRetention: false },
    series: [
      {
        seriesRef: { instanceId: 'cache-01', placementId: null, sourceKey: null },
        label: 'cache-01',
        state: 'ok',
        replicaComposition: { total: 1, inactive: 0 },
        color: 'steelblue',
        dashPattern: 'solid',
        points: [
          { ts: '2026-08-30T00:00:00Z', value: 1, lowCoverage: false },
          { ts: '2026-08-30T00:01:00Z', value: 2, lowCoverage: false },
        ],
        gaps: [],
      },
    ],
  };
}

describe('ChartRenderer a11y', () => {
  it.each(modes)('has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <ChartRenderer model={model()} ariaLabel="Cache evictions" />,
      { mode },
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
