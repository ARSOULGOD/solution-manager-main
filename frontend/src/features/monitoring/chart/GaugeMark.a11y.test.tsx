import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import type { ChartModel } from '@contracts/metrics/types';

import { renderWithProviders } from '../../../test/renderWithProviders';

import { GaugeMark } from './GaugeMark';

const modes = ['light', 'dark'] as const;

function model(points: ChartModel['series'][number]['points']): ChartModel {
  return {
    metric: { name: 'cache.hit_rate', metricType: 'gauge', unit: 'ratio' },
    render: { transform: 'none' },
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

describe('GaugeMark a11y', () => {
  it.each(modes)('has no violations, in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <GaugeMark model={model([{ ts: '2026-08-30T00:00:00Z', value: 0.87, lowCoverage: false }])} min={0} max={1} />,
      { mode },
    );
    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('has no violations in the empty state, in %s mode', async (mode) => {
    const { container } = renderWithProviders(<GaugeMark model={model([])} />, { mode });
    expect(await axe(container)).toHaveNoViolations();
  });
});
