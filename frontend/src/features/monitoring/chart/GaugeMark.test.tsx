import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import type { ChartModel, ChartSeries } from '@contracts/metrics/types';

import { renderWithProviders } from '../../../test/renderWithProviders';

import { GaugeMark } from './GaugeMark';

function series(overrides: Partial<ChartSeries> = {}): ChartSeries {
  return {
    seriesRef: { instanceId: 'agg', placementId: null, sourceKey: null },
    label: 'aggregate · 3 instances',
    state: 'ok',
    replicaComposition: { total: 1, inactive: 0 },
    color: 'steelblue',
    dashPattern: 'solid',
    points: [{ ts: '2026-08-30T00:00:00Z', value: 42, lowCoverage: false }],
    gaps: [],
    ...overrides,
  };
}

function model(overrides: Partial<ChartModel> = {}): ChartModel {
  return {
    metric: { name: 'cache.hit_rate', metricType: 'gauge', unit: 'count' },
    render: { transform: 'none' },
    coverage: { requestedFrom: '2026-08-30T00:00:00Z', servedFrom: '2026-08-30T00:00:00Z', truncatedByRetention: false },
    series: [series()],
    ...overrides,
  };
}

describe('GaugeMark', () => {
  it('renders the aggregated series last point, unit-formatted', () => {
    renderWithProviders(<GaugeMark model={model()} min={0} max={100} />);
    expect(screen.getByText('42 count')).toBeInTheDocument();
    expect(screen.getByRole('img', { name: 'Gauge: 42 count' })).toBeInTheDocument();
  });

  it('renders a stated empty state instead of a zero when there is no rendered point', () => {
    renderWithProviders(<GaugeMark model={model({ series: [series({ points: [] })] })} />);
    expect(screen.getByText('No data in range')).toBeInTheDocument();
    expect(screen.queryByText('0')).not.toBeInTheDocument();
  });

  it('renders a stated empty state when no series is present', () => {
    renderWithProviders(<GaugeMark model={model({ series: [] })} />);
    expect(screen.getByText('No data in range')).toBeInTheDocument();
  });

  it('clamps the filled fraction to the [min, max] range', () => {
    const { container } = renderWithProviders(
      <GaugeMark model={model({ series: [series({ points: [{ ts: '2026-08-30T00:00:00Z', value: 999, lowCoverage: false }] })] })} min={0} max={100} />,
    );
    const arcs = container.querySelectorAll('path');
    // Second path is the filled arc — its dasharray's first number is clamped to the full circumference.
    const filled = arcs[1];
    const dasharray = filled?.getAttribute('stroke-dasharray') ?? '';
    const [filledLength, circumference] = dasharray.split(' ').map(Number);
    expect(filledLength).toBeCloseTo(circumference ?? 0, 5);
  });
});
