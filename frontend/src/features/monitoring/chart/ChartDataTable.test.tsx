import { ThemeProvider } from '@mui/material/styles';
import { render, screen, within } from '@testing-library/react';
import type { ReactElement } from 'react';
import { describe, expect, it, vi } from 'vitest';

import type { ChartModel, ChartPoint, ChartSeries } from '@contracts/metrics/types';
import { formatDateTime, formatUnitValue } from '@shared/format';
import { createSmTheme } from '@shared/theme';

import { ChartDataTable } from './ChartDataTable';

function point(ts: string, value: number, lowCoverage = false): ChartPoint {
  return { ts, value, lowCoverage };
}

function series(overrides: Partial<ChartSeries> = {}): ChartSeries {
  return {
    seriesRef: { instanceId: 'cache-01', placementId: null, sourceKey: null },
    label: 'cache-01',
    state: 'ok',
    replicaComposition: { total: 1, inactive: 0 },
    color: 'steelblue',
    dashPattern: 'solid',
    points: [point('2026-08-30T00:00:00Z', 1), point('2026-08-30T00:01:00Z', 2)],
    gaps: [],
    ...overrides,
  };
}

function model(overrides: Partial<ChartModel> = {}): ChartModel {
  return {
    metric: { name: 'cache.evictions', metricType: 'counter', unit: 'count' },
    render: { transform: 'rate' },
    coverage: { requestedFrom: '2026-08-30T00:00:00Z', servedFrom: '2026-08-30T00:00:00Z', truncatedByRetention: false },
    series: [series()],
    ...overrides,
  };
}

function renderTable(ui: ReactElement) {
  return render(<ThemeProvider theme={createSmTheme('light')}>{ui}</ThemeProvider>);
}

describe('ChartDataTable', () => {
  it('renders nothing into the dialog while closed', () => {
    renderTable(<ChartDataTable model={model()} open={false} onClose={vi.fn()} />);
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('contains every series and every rendered point once open', () => {
    const chartModel = model({
      series: [
        series({
          label: 'node-a',
          points: [point('2026-08-30T00:00:00Z', 1), point('2026-08-30T00:01:00Z', 2)],
        }),
        series({
          seriesRef: { instanceId: 'cache-02', placementId: null, sourceKey: null },
          label: 'node-b',
          points: [point('2026-08-30T00:00:00Z', 9)],
        }),
      ],
    });
    renderTable(<ChartDataTable model={chartModel} open onClose={vi.fn()} />);

    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText('node-a')).toBeInTheDocument();
    expect(within(dialog).getByText('node-b')).toBeInTheDocument();

    const tableA = within(dialog).getByRole('table', { name: 'node-a' });
    expect(within(tableA).getByText(formatDateTime('2026-08-30T00:00:00Z'))).toBeInTheDocument();
    expect(within(tableA).getByText(formatDateTime('2026-08-30T00:01:00Z'))).toBeInTheDocument();
    expect(within(tableA).getByText(formatUnitValue(1, 'count'))).toBeInTheDocument();
    expect(within(tableA).getByText(formatUnitValue(2, 'count'))).toBeInTheDocument();

    const tableB = within(dialog).getByRole('table', { name: 'node-b' });
    expect(within(tableB).getByText(formatUnitValue(9, 'count'))).toBeInTheDocument();
  });

  it('flags a low-coverage point in its own row, everything else full coverage', () => {
    const chartModel = model({
      series: [series({ points: [point('2026-08-30T00:00:00Z', 1, false), point('2026-08-30T00:01:00Z', 2, true)] })],
    });
    renderTable(<ChartDataTable model={chartModel} open onClose={vi.fn()} />);

    const rows = screen.getAllByRole('row');
    // rows[0] is the header row.
    expect(within(rows[1] as HTMLElement).getByText('Full coverage')).toBeInTheDocument();
    expect(within(rows[2] as HTMLElement).getByText('Low coverage')).toBeInTheDocument();
  });

  it('renders a stated-empty message for a series with no points, no empty table', () => {
    const chartModel = model({ series: [series({ points: [] })] });
    renderTable(<ChartDataTable model={chartModel} open onClose={vi.fn()} />);

    expect(screen.getByText('No points rendered for this series in the selected range.')).toBeInTheDocument();
    expect(screen.queryByRole('table')).toBeNull();
  });

  it('renders the text summary — metric, range, series count', () => {
    const chartModel = model({
      metric: { name: 'cache.evictions', metricType: 'counter', unit: 'count' },
      series: [series({ label: 'a' }), series({ seriesRef: { instanceId: 'cache-02', placementId: null, sourceKey: null }, label: 'b' })],
    });
    renderTable(<ChartDataTable model={chartModel} open onClose={vi.fn()} />);

    expect(screen.getByRole('dialog', { name: /cache\.evictions/ })).toBeInTheDocument();
    expect(screen.getByText('2 series')).toBeInTheDocument();
  });

  it('states plainly when no series has a single rendered point', () => {
    const chartModel = model({ series: [series({ points: [] })] });
    renderTable(<ChartDataTable model={chartModel} open onClose={vi.fn()} />);

    expect(screen.getByText('No data points in range.')).toBeInTheDocument();
  });

  it('titles the dialog from the widget title when given, falling back to the metric name', () => {
    renderTable(<ChartDataTable model={model()} open onClose={vi.fn()} title="Eviction rate" />);
    expect(screen.getByRole('dialog', { name: /Eviction rate/ })).toBeInTheDocument();
  });

  it('carries a per-series state tag matching the tooltip/legend vocabulary', () => {
    const chartModel = model({ series: [series({ state: 'type-suspect' })] });
    renderTable(<ChartDataTable model={chartModel} open onClose={vi.fn()} />);
    expect(screen.getByText('type-suspect')).toBeInTheDocument();
  });
});
