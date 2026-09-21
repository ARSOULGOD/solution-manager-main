import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import type { ChartModel } from '@contracts/metrics/types';

import { renderWithProviders } from '../../../test/renderWithProviders';

import { SingleStatMark } from './SingleStatMark';

const modes = ['light', 'dark'] as const;

function model(points: ChartModel['series'][number]['points']): ChartModel {
  return {
    metric: { name: 'cache.evictions', metricType: 'counter', unit: 'evictions/min' },
    render: { transform: 'rate' },
    coverage: { requestedFrom: '2026-08-30T00:00:00Z', servedFrom: '2026-08-30T00:00:00Z', truncatedByRetention: false },
    series: [
      {
        seriesRef: { instanceId: 'agg', placementId: null, sourceKey: null },
        label: 'aggregate · 3 instances',
        state: 'ok',
        replicaComposition: { total: 1, inactive: 0 },
        color: 'steelblue',
        dashPattern: 'solid',
        points,
        gaps: [],
      },
    ],
  };
}

describe('SingleStatMark a11y', () => {
  it.each(modes)('has no violations, in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <SingleStatMark model={model([{ ts: '2026-08-30T00:00:00Z', value: 87, lowCoverage: false }])} delta={5} />,
      { mode },
    );
    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('has no violations in the empty state, in %s mode', async (mode) => {
    const { container } = renderWithProviders(<SingleStatMark model={model([])} />, { mode });
    expect(await axe(container)).toHaveNoViolations();
  });
});
