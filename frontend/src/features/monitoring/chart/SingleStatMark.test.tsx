import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import type { ChartModel, ChartSeries } from '@contracts/metrics/types';

import { renderWithProviders } from '../../../test/renderWithProviders';

import { SingleStatMark } from './SingleStatMark';

function series(overrides: Partial<ChartSeries> = {}): ChartSeries {
  return {
    seriesRef: { instanceId: 'agg', placementId: null, sourceKey: null },
    label: 'aggregate · 3 instances',
    state: 'ok',
    replicaComposition: { total: 1, inactive: 0 },
    color: 'steelblue',
    dashPattern: 'solid',
    points: [{ ts: '2026-08-30T00:00:00Z', value: 87, lowCoverage: false }],
    gaps: [],
    ...overrides,
  };
}

function model(overrides: Partial<ChartModel> = {}): ChartModel {
  return {
    metric: { name: 'cache.evictions', metricType: 'counter', unit: 'evictions/min' },
    render: { transform: 'rate' },
    coverage: { requestedFrom: '2026-08-30T00:00:00Z', servedFrom: '2026-08-30T00:00:00Z', truncatedByRetention: false },
    series: [series()],
    ...overrides,
  };
}

describe('SingleStatMark', () => {
  it('renders the aggregated series last point, unit-formatted', () => {
    renderWithProviders(<SingleStatMark model={model()} />);
    expect(screen.getByText('87 evictions/min')).toBeInTheDocument();
  });

  it('renders no delta line when delta is not supplied', () => {
    renderWithProviders(<SingleStatMark model={model()} />);
    expect(screen.queryByText(/▲|▼/)).not.toBeInTheDocument();
  });

  it('renders an up marker for a non-negative delta', () => {
    renderWithProviders(<SingleStatMark model={model()} delta={5} />);
    expect(screen.getByText('▲ 5 evictions/min')).toBeInTheDocument();
  });

  it('renders a down marker for a negative delta, magnitude only', () => {
    renderWithProviders(<SingleStatMark model={model()} delta={-5} />);
    expect(screen.getByText('▼ 5 evictions/min')).toBeInTheDocument();
  });

  it('renders a stated empty state instead of a zero when there is no rendered point', () => {
    renderWithProviders(<SingleStatMark model={model({ series: [series({ points: [] })] })} />);
    expect(screen.getByText('No data in range')).toBeInTheDocument();
  });

  it('renders a stated empty state when no series is present', () => {
    renderWithProviders(<SingleStatMark model={model({ series: [] })} />);
    expect(screen.getByText('No data in range')).toBeInTheDocument();
  });
});
