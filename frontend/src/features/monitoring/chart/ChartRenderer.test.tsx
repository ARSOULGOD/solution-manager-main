import { ThemeProvider } from '@mui/material/styles';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { ChartModel, ChartPoint, ChartSeries, ChartThreshold } from '@contracts/metrics/types';
import { formatAxisTimestamp } from '@shared/format';
import { createSmTheme, echartsTheme } from '@shared/theme';

import { resetReducedMotion, setReducedMotion, setViewportWidth } from '../../../test/matchMedia';

import { ChartRenderer, resolveScale } from './ChartRenderer';

/** The slice of `EChartsOption` these tests assert on — kept local so this
 * (non-exempt) test file never imports `echarts`'s own types (FE-D16). */
interface MockedOption {
  animation?: boolean;
  aria?: { enabled?: boolean };
  yAxis?: { type?: string; splitNumber?: number; axisLabel?: { formatter?: (value: number) => string } };
  xAxis?: { type?: string; boundaryGap?: [unknown, unknown]; axisLabel?: { hideOverlap?: boolean } };
  series?: Array<Record<string, unknown>>;
  graphic?: Array<{ style?: { text?: string } }>;
  grid?: { top?: number; left?: number; right?: number; bottom?: number; containLabel?: boolean };
  tooltip?: { trigger?: string; formatter?: (params: unknown) => string };
  legend?: {
    type?: string;
    orient?: string;
    height?: number;
    bottom?: number;
    data?: string[];
    formatter?: (name: string) => string;
  };
}

const mockChart = { setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn() };
const mockInit = vi.fn<(container: unknown, theme: unknown, opts: unknown) => typeof mockChart>(
  () => mockChart,
);

vi.mock('echarts', () => ({
  init: mockInit,
}));

function point(ts: string, value: number): ChartPoint {
  return { ts, value, lowCoverage: false };
}

function lowCoveragePoint(ts: string, value: number): ChartPoint {
  return { ts, value, lowCoverage: true };
}

function series(overrides: Partial<ChartSeries> = {}, pointCount = 3): ChartSeries {
  return {
    seriesRef: { instanceId: 'cache-01', placementId: null, sourceKey: null },
    label: 'cache-01',
    state: 'ok',
    replicaComposition: { total: 1, inactive: 0 },
    color: 'steelblue',
    dashPattern: 'solid',
    points: Array.from({ length: pointCount }, (_, i) => point(`2026-08-30T00:0${i}:00Z`, i)),
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

function renderChart(ui: ReactElement, mode: 'light' | 'dark' = 'light') {
  return render(<ThemeProvider theme={createSmTheme(mode)}>{ui}</ThemeProvider>);
}

async function waitForInit() {
  await waitFor(() => expect(mockInit).toHaveBeenCalledTimes(1));
}

function lastOption(): MockedOption {
  const call = mockChart.setOption.mock.calls.at(-1);
  if (call === undefined) {
    throw new Error('setOption was never called');
  }
  return call[0] as MockedOption;
}

beforeEach(() => {
  mockInit.mockClear();
  mockChart.setOption.mockClear();
  mockChart.resize.mockClear();
  mockChart.dispose.mockClear();
});

afterEach(() => {
  resetReducedMotion();
});

describe('ChartRenderer', () => {
  it('renders a visible marker when a live query has only one sample', async () => {
    renderChart(<ChartRenderer model={model({ series: [series({}, 1)] })} />);
    await waitForInit();

    expect(lastOption().series?.[0]).toMatchObject({ symbol: 'circle', symbolSize: 6 });
  });

  it('initializes echarts with the canvas renderer and the derived theme', async () => {
    const theme = createSmTheme('light');
    renderChart(<ChartRenderer model={model()} />);

    await waitForInit();

    const [, themeArg, initOpts] = mockInit.mock.calls[0] as [unknown, unknown, unknown];
    expect(themeArg).toEqual(echartsTheme(theme));
    expect(initOpts).toMatchObject({ renderer: 'canvas' });
  });

  it('sets one line series per ChartSeries, carrying colour, dash pattern and points', async () => {
    const chartModel = model({
      series: [
        series({ label: 'a', color: 'crimson', dashPattern: 'solid' }),
        series({ label: 'b', color: 'teal', dashPattern: 'dashed' }),
      ],
    });
    renderChart(<ChartRenderer model={chartModel} />);

    await waitFor(() => expect(mockChart.setOption).toHaveBeenCalled());

    const option = lastOption();
    const optionSeries = option.series as Array<Record<string, unknown>>;
    expect(optionSeries).toHaveLength(2);
    expect(optionSeries[0]).toMatchObject({
      type: 'line',
      name: 'a',
      connectNulls: false,
      lineStyle: { color: 'crimson', type: 'solid' },
    });
    expect(optionSeries[1]).toMatchObject({
      type: 'line',
      name: 'b',
      lineStyle: { color: 'teal', type: 'dashed' },
    });
    expect(optionSeries[0]?.['data']).toEqual([
      ['2026-08-30T00:00:00Z', 0],
      ['2026-08-30T00:01:00Z', 1],
      ['2026-08-30T00:02:00Z', 2],
    ]);
  });

  it('does not enable progressive rendering below the point threshold', async () => {
    renderChart(<ChartRenderer model={model({ series: [series({}, 5)] })} />);

    await waitFor(() => expect(mockChart.setOption).toHaveBeenCalled());

    const optionSeries = lastOption().series as Array<Record<string, unknown>>;
    expect(optionSeries[0]?.['progressive']).toBeUndefined();
  });

  it('enables progressive rendering above the point threshold', async () => {
    renderChart(<ChartRenderer model={model({ series: [series({}, 3001)] })} />);

    await waitFor(() => expect(mockChart.setOption).toHaveBeenCalled());

    const optionSeries = lastOption().series as Array<Record<string, unknown>>;
    expect(optionSeries[0]).toMatchObject({ progressive: 400, progressiveThreshold: 3000 });
  });

  it('reduced motion disables animation and skips progressive rendering even above the threshold', async () => {
    setReducedMotion(true);
    renderChart(<ChartRenderer model={model({ series: [series({}, 3001)] })} />);

    await waitFor(() => expect(mockChart.setOption).toHaveBeenCalled());

    const option = lastOption();
    expect(option.animation).toBe(false);
    const optionSeries = option.series as Array<Record<string, unknown>>;
    expect(optionSeries[0]?.['progressive']).toBeUndefined();
  });

  it('disposes the chart instance on unmount', async () => {
    const { unmount } = renderChart(<ChartRenderer model={model()} />);
    await waitForInit();

    unmount();

    expect(mockChart.dispose).toHaveBeenCalledTimes(1);
  });

  it('re-initializes on a theme-mode change but not on a data-only rerender', async () => {
    // Mirrors `app/providers.tsx`'s `useMemo(() => createSmTheme(mode), [mode])`:
    // the real app hands ChartRenderer the *same* theme object across a
    // data-only rerender, and only a genuinely new one when mode changes.
    const lightTheme = createSmTheme('light');
    const darkTheme = createSmTheme('dark');

    const { rerender } = render(
      <ThemeProvider theme={lightTheme}>
        <ChartRenderer model={model()} />
      </ThemeProvider>,
    );
    await waitForInit();

    rerender(
      <ThemeProvider theme={lightTheme}>
        <ChartRenderer model={model({ series: [series({ label: 'still-light' })] })} />
      </ThemeProvider>,
    );
    await waitFor(() => expect(mockChart.setOption).toHaveBeenCalledTimes(2));
    expect(mockInit).toHaveBeenCalledTimes(1);

    rerender(
      <ThemeProvider theme={darkTheme}>
        <ChartRenderer model={model()} />
      </ThemeProvider>,
    );
    await waitFor(() => expect(mockInit).toHaveBeenCalledTimes(2));
  });

  // FE-073 — gaps, coverage, thresholds, annotations.

  it('feeds a gap as null boundary markers, breaking the line, and shades it via markArea', async () => {
    const chartModel = model({
      series: [
        series({
          points: [point('2026-08-30T00:00:00Z', 1), point('2026-08-30T00:03:00Z', 4)],
          gaps: [{ from: '2026-08-30T00:01:00Z', to: '2026-08-30T00:02:00Z', reason: 'no-samples' }],
        }),
      ],
    });
    renderChart(<ChartRenderer model={chartModel} />);
    await waitFor(() => expect(mockChart.setOption).toHaveBeenCalled());

    const optionSeries = lastOption().series as Array<Record<string, unknown>>;
    expect(optionSeries[0]?.['data']).toEqual([
      ['2026-08-30T00:00:00Z', 1],
      { value: ['2026-08-30T00:01:00Z', null], symbol: 'none' },
      { value: ['2026-08-30T00:02:00Z', null], symbol: 'none' },
      ['2026-08-30T00:03:00Z', 4],
    ]);
    expect(optionSeries[0]?.['connectNulls']).toBe(false);
    const markArea = optionSeries[0]?.['markArea'] as { data?: unknown[] };
    expect(markArea.data).toEqual([
      [
        { xAxis: '2026-08-30T00:01:00Z', name: 'no-samples', itemStyle: expect.any(Object) as unknown },
        { xAxis: '2026-08-30T00:02:00Z' },
      ],
    ]);
  });

  it('never lets a gap-boundary null displace a real point at the same timestamp', async () => {
    const chartModel = model({
      series: [
        series({
          points: [point('2026-08-30T00:00:00Z', 1), point('2026-08-30T00:01:00Z', 2)],
          // A gap that (incorrectly) claims the same instant as a real point.
          gaps: [{ from: '2026-08-30T00:01:00Z', to: '2026-08-30T00:01:30Z', reason: 'no-samples' }],
        }),
      ],
    });
    renderChart(<ChartRenderer model={chartModel} />);
    await waitFor(() => expect(mockChart.setOption).toHaveBeenCalled());

    const optionSeries = lastOption().series as Array<Record<string, unknown>>;
    const data = optionSeries[0]?.['data'] as unknown[];
    expect(data[1]).toEqual(['2026-08-30T00:01:00Z', 2]);
  });

  it('renders low-coverage points as hollow markers, everything else symbol-less', async () => {
    const chartModel = model({
      series: [
        series({
          color: 'crimson',
          points: [point('2026-08-30T00:00:00Z', 1), lowCoveragePoint('2026-08-30T00:01:00Z', 2)],
        }),
      ],
    });
    renderChart(<ChartRenderer model={chartModel} />);
    await waitFor(() => expect(mockChart.setOption).toHaveBeenCalled());

    const optionSeries = lastOption().series as Array<Record<string, unknown>>;
    expect(optionSeries[0]).toMatchObject({ showSymbol: true, symbol: 'none' });
    const data = optionSeries[0]?.['data'] as unknown[];
    expect(data[0]).toEqual(['2026-08-30T00:00:00Z', 1]);
    expect(data[1]).toMatchObject({
      value: ['2026-08-30T00:01:00Z', 2],
      symbol: 'circle',
      itemStyle: { color: 'transparent', borderColor: 'crimson' },
    });
  });

  it('renders a retention-truncated band, a dashed boundary line, and a stated reason', async () => {
    const chartModel = model({
      coverage: {
        requestedFrom: '2026-08-23T00:00:00Z',
        servedFrom: '2026-08-30T00:00:00Z',
        truncatedByRetention: true,
        truncatedBand: { from: '2026-08-23T00:00:00Z', to: '2026-08-30T00:00:00Z' },
      },
    });
    renderChart(<ChartRenderer model={chartModel} />);
    await waitFor(() => expect(mockChart.setOption).toHaveBeenCalled());

    const optionSeries = lastOption().series as Array<Record<string, unknown>>;
    const markArea = optionSeries[0]?.['markArea'] as { data?: unknown[] };
    expect(markArea.data).toEqual([
      [
        { xAxis: '2026-08-23T00:00:00Z', name: 'retention-truncated', itemStyle: expect.any(Object) as unknown },
        { xAxis: '2026-08-30T00:00:00Z' },
      ],
    ]);
    const markLine = optionSeries[0]?.['markLine'] as { data?: Array<Record<string, unknown>> };
    const boundary = markLine.data?.find((d) => d['name'] === 'retention-boundary');
    expect(boundary).toMatchObject({
      xAxis: '2026-08-30T00:00:00Z',
      lineStyle: { type: 'dashed' },
      label: { formatter: 'Data before this point is outside the retention window' },
    });
  });

  it('renders thresholds as markLine carrying both colour and dash pattern', async () => {
    const thresholds: ChartThreshold[] = [
      { value: 100, label: 'warn', severity: 'warning' },
      { value: 200, label: 'crit', severity: 'critical', style: 'dotted' },
    ];
    renderChart(<ChartRenderer model={model()} thresholds={thresholds} />);
    await waitFor(() => expect(mockChart.setOption).toHaveBeenCalled());

    const optionSeries = lastOption().series as Array<Record<string, unknown>>;
    const markLine = optionSeries[0]?.['markLine'] as { data?: Array<Record<string, unknown>> };
    const warn = markLine.data?.find((d) => d['name'] === 'warn');
    const crit = markLine.data?.find((d) => d['name'] === 'crit');
    expect(warn).toMatchObject({ yAxis: 100, lineStyle: { type: 'dashed' } });
    expect(crit).toMatchObject({ yAxis: 200, lineStyle: { type: 'dotted' } });
    // Colour differs by severity — never the same channel carrying both.
    expect((warn?.['lineStyle'] as { color?: string }).color).not.toEqual(
      (crit?.['lineStyle'] as { color?: string }).color,
    );
  });

  it('renders a replica-count-change annotation as a dashed vertical marker with an accessible label', async () => {
    const chartModel = model({
      series: [series({ replicaCountChanges: [{ ts: '2026-08-30T00:01:00Z', delta: 1 }] })],
    });
    renderChart(<ChartRenderer model={chartModel} />);
    await waitFor(() => expect(mockChart.setOption).toHaveBeenCalled());

    const optionSeries = lastOption().series as Array<Record<string, unknown>>;
    const markLine = optionSeries[0]?.['markLine'] as { data?: Array<Record<string, unknown>> };
    const annotation = markLine.data?.find((d) => d['xAxis'] === '2026-08-30T00:01:00Z');
    expect(annotation).toMatchObject({
      lineStyle: { type: 'dashed' },
      label: { formatter: 'replica added (+1) — summed series steps; not a workload change' },
    });
  });

  it('renders inactive-replica and pending-config-change series dashed and dimmed', async () => {
    const chartModel = model({
      series: [series({ state: 'inactive-replica', dashPattern: 'solid' })],
    });
    renderChart(<ChartRenderer model={chartModel} />);
    await waitFor(() => expect(mockChart.setOption).toHaveBeenCalled());

    const optionSeries = lastOption().series as Array<Record<string, unknown>>;
    expect(optionSeries[0]).toMatchObject({ lineStyle: { type: 'dashed', opacity: 0.6 } });
  });

  describe('fill (DM-D12 — "area" is an option on line, not a mark)', () => {
    it('omits areaStyle by default', async () => {
      renderChart(<ChartRenderer model={model()} />);
      await waitFor(() => expect(mockChart.setOption).toHaveBeenCalled());
      const optionSeries = lastOption().series as Array<Record<string, unknown>>;
      expect(optionSeries[0]?.['areaStyle']).toBeUndefined();
    });

    it('adds areaStyle to every series when fill is true, without changing type to anything but line', async () => {
      const chartModel = model({ series: [series({ label: 'a' }), series({ label: 'b' })] });
      renderChart(<ChartRenderer model={chartModel} fill />);
      await waitFor(() => expect(mockChart.setOption).toHaveBeenCalled());
      const optionSeries = lastOption().series as Array<Record<string, unknown>>;
      expect(optionSeries).toHaveLength(2);
      for (const s of optionSeries) {
        expect(s['type']).toBe('line');
        expect(s['areaStyle']).toEqual({});
      }
    });
  });

  describe('resolveScale', () => {
    it('disables log scale for a counter rate, with a stated reason', () => {
      const counterRate = model({ metric: { name: 'x', metricType: 'counter', unit: null }, render: { transform: 'rate' } });
      const resolution = resolveScale(counterRate, 'log');
      expect(resolution.type).toBe('value');
      expect(resolution.disabledReason).toBeDefined();
    });

    it('leaves log scale available for a gauge', () => {
      const gauge = model({ metric: { name: 'x', metricType: 'gauge', unit: null }, render: { transform: 'none' } });
      expect(resolveScale(gauge, 'log')).toEqual({ type: 'log' });
    });

    it('never restricts linear scale', () => {
      const counterRate = model({ metric: { name: 'x', metricType: 'counter', unit: null }, render: { transform: 'rate' } });
      expect(resolveScale(counterRate, 'linear')).toEqual({ type: 'value' });
    });
  });

  it('a counter-rate fixture requesting log scale stays linear in the option, with no exclusion notice', async () => {
    const chartModel = model({
      metric: { name: 'cache.evictions', metricType: 'counter', unit: 'count' },
      render: { transform: 'rate' },
    });
    renderChart(<ChartRenderer model={chartModel} scale="log" />);
    await waitFor(() => expect(mockChart.setOption).toHaveBeenCalled());

    const option = lastOption();
    expect(option.yAxis?.type).toBe('value');
    expect(option.graphic).toBeUndefined();
  });

  it('excludes non-positive values under a log-scale gauge, with a visible notice, never silently', async () => {
    const chartModel = model({
      metric: { name: 'cache.hit_rate', metricType: 'gauge', unit: 'ratio' },
      render: { transform: 'none' },
      series: [
        series({
          points: [point('2026-08-30T00:00:00Z', 5), point('2026-08-30T00:01:00Z', 0), point('2026-08-30T00:02:00Z', -1)],
        }),
      ],
    });
    renderChart(<ChartRenderer model={chartModel} scale="log" />);
    await waitFor(() => expect(mockChart.setOption).toHaveBeenCalled());

    const option = lastOption();
    expect(option.yAxis?.type).toBe('log');
    const optionSeries = option.series as Array<Record<string, unknown>>;
    const data = optionSeries[0]?.['data'] as unknown[];
    expect(data[0]).toEqual(['2026-08-30T00:00:00Z', 5]);
    expect(data[1]).toMatchObject({ value: ['2026-08-30T00:01:00Z', null], symbol: 'none' });
    expect(data[2]).toMatchObject({ value: ['2026-08-30T00:02:00Z', null], symbol: 'none' });
    expect(option.graphic?.[0]?.style?.text).toContain('2 non-positive values excluded');
  });

  // FE-074 — tooltip and legend.

  describe('tooltip', () => {
    const cursorTs = '2026-08-30T00:01:00Z';
    const cursorMs = Date.parse(cursorTs);

    async function optionFor(chartModel: ChartModel) {
      renderChart(<ChartRenderer model={chartModel} />);
      await waitFor(() => expect(mockChart.setOption).toHaveBeenCalled());
      return lastOption();
    }

    it('is a shared-axis tooltip, sorted by value descending, with a per-series state tag', async () => {
      const chartModel = model({
        series: [
          series({ label: 'node-alpha', state: 'ok', points: [point(cursorTs, 5)] }),
          series({ label: 'node-beta', state: 'inactive-replica', points: [point(cursorTs, 20)] }),
        ],
      });
      const option = await optionFor(chartModel);

      const html = option.tooltip?.formatter?.([{ axisValue: cursorMs }]) ?? '';
      expect(html.indexOf('node-beta')).toBeLessThan(html.indexOf('node-alpha'));
      expect(html).toContain('inactive replica');
      expect(html).toContain(formatAxisTimestamp(new Date(cursorMs).toISOString()));
    });

    it('renders `gap` — never blank, never zero — for a series with no point at the cursor', async () => {
      const chartModel = model({
        series: [
          series({ label: 'has-point', points: [point(cursorTs, 5)] }),
          series({
            label: 'gapped',
            state: 'type-suspect',
            points: [point('2026-08-30T00:00:00Z', 1), point('2026-08-30T00:02:00Z', 1)],
          }),
        ],
      });
      const option = await optionFor(chartModel);

      const html = option.tooltip?.formatter?.([{ axisValue: cursorMs }]) ?? '';
      // The gap row shows the literal word "gap", not the series' own
      // declared state (`type-suspect`) — there is nothing to tag a value
      // with when there is no value.
      expect(html).toContain('gap');
      expect(html).not.toContain('type-suspect');
      // Gap rows have no value to sort by, so they always sort last.
      expect(html.indexOf('has-point')).toBeLessThan(html.indexOf('gapped'));
    });

    it('formats the row value from the metric unit', async () => {
      const chartModel = model({
        metric: { name: 'cache.hit_rate', metricType: 'gauge', unit: 'ms' },
        series: [series({ points: [point(cursorTs, 1234)] })],
      });
      const option = await optionFor(chartModel);

      const html = option.tooltip?.formatter?.([{ axisValue: cursorMs }]) ?? '';
      expect(html).toContain('1,234 ms');
    });

    it('renders nothing when the axis position carries no recognisable value', async () => {
      const option = await optionFor(model());
      expect(option.tooltip?.formatter?.([{}])).toBe('');
    });
  });

  describe('legend', () => {
    it('never truncates membership — every plotted series gets an entry', async () => {
      const chartModel = model({
        series: Array.from({ length: 5 }, (_, i) => series({ label: `s${i}` })),
      });
      renderChart(<ChartRenderer model={chartModel} legendPosition="right" />);
      await waitFor(() => expect(mockChart.setOption).toHaveBeenCalled());

      const legend = lastOption().legend;
      expect(legend?.data).toEqual(['s0', 's1', 's2', 's3', 's4']);
      expect(legend?.type).toBe('scroll');
      expect(legend?.orient).toBe('vertical');
    });

    it('bounds height by legendMaxHeightRows without reducing membership', async () => {
      renderChart(<ChartRenderer model={model()} legendPosition="right" legendMaxHeightRows={2} />);
      await waitFor(() => expect(mockChart.setOption).toHaveBeenCalled());

      const legend = lastOption().legend;
      expect(legend?.height).toBeGreaterThan(0);
      expect(legend?.data).toHaveLength(1);
    });

    it('appends a state tag for a non-ok series and leaves an ok series plain', async () => {
      const chartModel = model({
        series: [series({ label: 'suspect', state: 'type-suspect' }), series({ label: 'plain', state: 'ok' })],
      });
      renderChart(<ChartRenderer model={chartModel} />);
      await waitFor(() => expect(mockChart.setOption).toHaveBeenCalled());

      const formatter = lastOption().legend?.formatter;
      expect(formatter?.('suspect')).toContain('type-suspect');
      expect(formatter?.('plain')).toBe('plain');
    });
  });

  it('reserves grid space for the vertical legend', async () => {
    renderChart(<ChartRenderer model={model()} legendPosition="right" />);
    await waitFor(() => expect(mockChart.setOption).toHaveBeenCalled());

    expect(lastOption().grid?.right).toBeGreaterThan(0);
  });

  // Task 128 — chart legibility. Before this, `grid` set only `right`, so
  // ECharts' own `top: 60` / `bottom: 60` / `left: '10%'` defaults consumed
  // ~120px of vertical space inside a 220px widget body.
  describe('plot box and axis legibility (task 128)', () => {
    it('states the grid on all four sides and contains the labels', async () => {
      renderChart(<ChartRenderer model={model()} />);
      await waitFor(() => expect(mockChart.setOption).toHaveBeenCalled());

      const grid = lastOption().grid;
      expect(grid?.containLabel).toBe(true);
      // Every side is stated — none is left to an ECharts default.
      for (const side of [grid?.top, grid?.left, grid?.right, grid?.bottom]) {
        expect(side).toBeTypeOf('number');
      }
      // ...and every inset is far smaller than the defaults it replaces.
      expect(grid?.top).toBeLessThan(60);
      expect(grid?.left).toBeLessThan(60);
    });

    it('defaults the legend below the plot, reserving bottom rather than right', async () => {
      // `WidgetConfigV1.legend.position` has only ever been `'bottom'`;
      // this used to be ignored while 180px of width was reserved on the
      // right instead.
      renderChart(<ChartRenderer model={model()} />);
      await waitFor(() => expect(mockChart.setOption).toHaveBeenCalled());

      expect(lastOption().legend?.orient).toBe('horizontal');
      expect(lastOption().grid?.bottom).toBeGreaterThan(lastOption().grid?.top ?? 0);
      expect(lastOption().grid?.right).toBeLessThan(100);
    });

    it('formats y tick labels compactly, without repeating the unit', async () => {
      const chartModel = model();
      renderChart(<ChartRenderer model={chartModel} />);
      await waitFor(() => expect(mockChart.setOption).toHaveBeenCalled());

      const formatter = lastOption().yAxis?.axisLabel?.formatter;
      expect(formatter).toBeTypeOf('function');
      // The fixture metric's unit is `count`; a tick reads `47.9k`, never
      // `47,900 count` (see `formatAxisValue`).
      expect(formatter?.(47_900)).toBe('47.9k');
      expect(formatter?.(42)).toBe('42');
    });

    it('asks for a bounded number of y ticks', async () => {
      renderChart(<ChartRenderer model={model()} />);
      await waitFor(() => expect(mockChart.setOption).toHaveBeenCalled());

      const splitNumber = lastOption().yAxis?.splitNumber;
      expect(splitNumber).toBeGreaterThanOrEqual(2);
      expect(splitNumber).toBeLessThanOrEqual(6);
    });

    it('hides overlapping x labels and pads the time axis flush', async () => {
      renderChart(<ChartRenderer model={model()} />);
      await waitFor(() => expect(mockChart.setOption).toHaveBeenCalled());

      expect(lastOption().xAxis?.axisLabel?.hideOverlap).toBe(true);
      // A time axis takes `[start, end]`, never a boolean — `boundaryGap:
      // false` is category-axis-only.
      expect(lastOption().xAxis?.boundaryGap).toEqual([0, 0]);
    });
  });

  it('enables ECharts aria', async () => {
    renderChart(<ChartRenderer model={model()} />);
    await waitFor(() => expect(mockChart.setOption).toHaveBeenCalled());

    expect(lastOption().aria).toEqual({ enabled: true });
  });

  // FE-075 — chart accessibility (`dynamic-metrics` design §18).

  describe('keyboard accessibility', () => {
    function twoSeriesModel(): ChartModel {
      return model({
        series: [
          series({
            label: 'node-a',
            points: [point('2026-08-30T00:00:00Z', 5), point('2026-08-30T00:01:00Z', 6)],
          }),
          series({
            seriesRef: { instanceId: 'cache-02', placementId: null, sourceKey: null },
            label: 'node-b',
            points: [point('2026-08-30T00:00:00Z', 50), point('2026-08-30T00:01:00Z', 60)],
          }),
        ],
      });
    }

    function chartRegion() {
      return screen.getByRole('application');
    }

    it('is one focus stop', async () => {
      renderChart(<ChartRenderer model={twoSeriesModel()} />);
      await waitFor(() => expect(mockChart.setOption).toHaveBeenCalled());

      expect(chartRegion()).toHaveAttribute('tabindex', '0');
    });

    it('ArrowRight/ArrowLeft move the cursor along the time axis', async () => {
      renderChart(<ChartRenderer model={twoSeriesModel()} />);
      await waitFor(() => expect(mockChart.setOption).toHaveBeenCalled());
      const region = chartRegion();

      expect(screen.getByText(/node-a, .*: 5/)).toBeInTheDocument();

      fireEvent.keyDown(region, { key: 'ArrowRight' });
      expect(screen.getByText(/node-a, .*: 6/)).toBeInTheDocument();

      fireEvent.keyDown(region, { key: 'ArrowLeft' });
      expect(screen.getByText(/node-a, .*: 5/)).toBeInTheDocument();

      // Already at the first point — ArrowLeft again must not go negative.
      fireEvent.keyDown(region, { key: 'ArrowLeft' });
      expect(screen.getByText(/node-a, .*: 5/)).toBeInTheDocument();
    });

    it('ArrowUp/ArrowDown change the focused series', async () => {
      renderChart(<ChartRenderer model={twoSeriesModel()} />);
      await waitFor(() => expect(mockChart.setOption).toHaveBeenCalled());
      const region = chartRegion();

      expect(screen.getByText(/node-a, .*: 5/)).toBeInTheDocument();

      fireEvent.keyDown(region, { key: 'ArrowDown' });
      expect(screen.getByText(/node-b, .*: 50/)).toBeInTheDocument();

      fireEvent.keyDown(region, { key: 'ArrowUp' });
      expect(screen.getByText(/node-a, .*: 5/)).toBeInTheDocument();
    });

    it('announces `gap` — never blank, never zero — when the focused series has no point at the cursor', async () => {
      const chartModel = model({
        series: [
          series({ label: 'has-point', points: [point('2026-08-30T00:00:00Z', 1), point('2026-08-30T00:01:00Z', 2)] }),
          series({
            seriesRef: { instanceId: 'cache-02', placementId: null, sourceKey: null },
            label: 'gapped',
            points: [point('2026-08-30T00:00:00Z', 1)],
          }),
        ],
      });
      renderChart(<ChartRenderer model={chartModel} />);
      await waitFor(() => expect(mockChart.setOption).toHaveBeenCalled());
      const region = chartRegion();

      fireEvent.keyDown(region, { key: 'ArrowDown' });
      fireEvent.keyDown(region, { key: 'ArrowRight' });

      expect(screen.getByText(/gapped, .*: gap/)).toBeInTheDocument();
    });

    it('Enter opens the tabular alternative', async () => {
      renderChart(<ChartRenderer model={twoSeriesModel()} ariaLabel="Cache evictions" />);
      await waitFor(() => expect(mockChart.setOption).toHaveBeenCalled());

      expect(screen.queryByRole('dialog')).toBeNull();

      fireEvent.keyDown(chartRegion(), { key: 'Enter' });

      const dialog = screen.getByRole('dialog');
      expect(within(dialog).getByText('node-a')).toBeInTheDocument();
      expect(within(dialog).getByText('node-b')).toBeInTheDocument();
    });

    it('the visible "Open data table" button opens the same tabular alternative', async () => {
      renderChart(<ChartRenderer model={twoSeriesModel()} />);
      await waitFor(() => expect(mockChart.setOption).toHaveBeenCalled());

      fireEvent.click(screen.getByRole('button', { name: 'Open data table' }));

      expect(screen.getByRole('dialog')).toBeInTheDocument();
    });
  });

  // Task 109 — two independent width thresholds (this file's own header).
  describe('responsive legend and tabular alternative (task 109, FE-D29)', () => {
    it('under md the legend moves below the plot and stays a scrolling (never-truncating) legend', async () => {
      setViewportWidth(700); // between sm (600) and md (905)
      renderChart(<ChartRenderer model={model()} />);
      await waitFor(() => expect(mockChart.setOption).toHaveBeenCalled());

      const legend = lastOption().legend;
      expect(legend?.orient).toBe('horizontal');
      expect(legend?.type).toBe('scroll');
      // Every series is still present — the horizontal legend paginates, it
      // does not drop membership (G12 / §12: "never truncates").
      expect(legend?.data).toEqual(['cache-01']);
      expect(lastOption().grid?.bottom).toBeGreaterThan(0);
    });

    it('at md and up a caller asking for a right-hand legend keeps it vertical', async () => {
      setViewportWidth(1280);
      renderChart(<ChartRenderer model={model()} legendPosition="right" />);
      await waitFor(() => expect(mockChart.setOption).toHaveBeenCalled());

      const legend = lastOption().legend;
      expect(legend?.orient).toBe('vertical');
      expect(lastOption().grid?.right).toBeGreaterThan(0);
    });

    it('below sm the canvas is hidden (never unmounted) and the tabular alternative renders inline as the primary affordance', async () => {
      setViewportWidth(320);
      const chartModel = model({ series: [series({ label: 'node-a' })] });
      renderChart(<ChartRenderer model={chartModel} ariaLabel="Cache evictions" />);
      await waitFor(() => expect(mockChart.setOption).toHaveBeenCalled());

      // Offered directly, not behind Enter/the button — no dialog, the
      // table content is already on screen.
      expect(screen.queryByRole('dialog')).toBeNull();
      expect(screen.getByRole('table', { name: 'node-a' })).toBeInTheDocument();

      // Still mounted, not removed — see this file's header on why (a live
      // resize back up must find the same `ref`-attached node, never a
      // freshly created one `useEcharts` has no way to notice).
      const region = screen.getByRole('application', { hidden: true });
      expect(region.parentElement).toHaveStyle({ display: 'none' });
    });

    it('crossing back above sm restores the canvas and hides the inline table', async () => {
      setViewportWidth(320);
      const { rerender } = renderChart(<ChartRenderer model={model()} />);
      await waitFor(() => expect(mockChart.setOption).toHaveBeenCalled());
      expect(screen.getByRole('table')).toBeInTheDocument();

      setViewportWidth(1280);
      rerender(
        <ThemeProvider theme={createSmTheme('light')}>
          <ChartRenderer model={model()} />
        </ThemeProvider>,
      );

      expect(screen.queryByRole('table')).toBeNull();
      expect(screen.getByRole('application')).toBeInTheDocument();
    });
  });
});
