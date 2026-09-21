import { describe, expect, it } from 'vitest';

import { createSmTheme } from './createSmTheme';
import { echartsTheme } from './echartsTheme';

describe('echartsTheme', () => {
  it('is pure: same input produces a deep-equal output', () => {
    const theme = createSmTheme('light');

    expect(echartsTheme(theme)).toEqual(echartsTheme(theme));
  });

  it('output changes with mode', () => {
    const light = echartsTheme(createSmTheme('light'));
    const dark = echartsTheme(createSmTheme('dark'));

    expect(light).not.toEqual(dark);
    expect(light.textStyle.color).not.toBe(dark.textStyle.color);
    expect(light.tooltip.backgroundColor).not.toBe(dark.tooltip.backgroundColor);
  });

  it('derives every colour from the theme, never a literal', () => {
    const theme = createSmTheme('light');
    const chart = echartsTheme(theme);

    expect(chart.color).toEqual(theme.palette.chart.categorical);
    expect(chart.valueAxis.axisLine.lineStyle.color).toBe(theme.palette.divider);
    expect(chart.tooltip.borderColor).toBe(theme.palette.divider);
    expect(chart.legend.textStyle.color).toBe(theme.palette.text.secondary);
  });

  it('carries the theme font family through every text style', () => {
    const theme = createSmTheme('light');
    const chart = echartsTheme(theme);

    expect(chart.textStyle.fontFamily).toBe(theme.typography.fontFamily);
    expect(chart.valueAxis.axisLabel.fontFamily).toBe(theme.typography.fontFamily);
    expect(chart.tooltip.textStyle.fontFamily).toBe(theme.typography.fontFamily);
  });

  /**
   * The defect task 127 fixes: these four keys are the ONLY ones an axis
   * model reads a theme from — `coord/axisModelCreator.js:69` merges
   * `themeModel.get(axisType + 'Axis')` for each of `AXIS_TYPES`
   * (`coord/axisCommonTypes.js:44-49`). Styling parked at the top level was
   * silently ignored, so charts drew with stock ECharts axis colours.
   */
  const AXIS_FAMILIES = ['timeAxis', 'valueAxis', 'logAxis', 'categoryAxis'] as const;

  it.each(AXIS_FAMILIES)('styles the %s family from the theme, not ECharts defaults', (family) => {
    const theme = createSmTheme('light');
    const axis = echartsTheme(theme)[family];

    expect(axis.axisLine.lineStyle.color).toBe(theme.palette.divider);
    expect(axis.axisTick.lineStyle.color).toBe(theme.palette.divider);
    expect(axis.splitLine.lineStyle.color).toBe(theme.palette.divider);
    expect(axis.axisLabel.color).toBe(theme.palette.text.secondary);
    expect(axis.axisLabel.fontFamily).toBe(theme.typography.fontFamily);
  });

  it('exposes no top-level axis keys, which ECharts would never read', () => {
    const chart = echartsTheme(createSmTheme('light')) as unknown as Record<string, unknown>;

    for (const key of ['axisLine', 'axisTick', 'axisLabel', 'splitLine']) {
      expect(chart).not.toHaveProperty(key);
    }
  });

  it('all four axis families track the mode together', () => {
    const light = echartsTheme(createSmTheme('light'));
    const dark = echartsTheme(createSmTheme('dark'));

    for (const family of AXIS_FAMILIES) {
      expect(light[family].axisLabel.color).not.toBe(dark[family].axisLabel.color);
      expect(light[family].splitLine.lineStyle.color).not.toBe(dark[family].splitLine.lineStyle.color);
    }
  });

  /**
   * `reference/app/charts.jsx` draws horizontal gridlines only. The y axis
   * is `value` or `log` (`ChartRenderer.resolveScale`) and the x axis is
   * `time`, so the split lines belong to the former and the axis line to
   * the latter.
   */
  it('draws horizontal gridlines only, matching the reference', () => {
    const chart = echartsTheme(createSmTheme('light'));

    for (const family of ['valueAxis', 'logAxis'] as const) {
      expect(chart[family].splitLine.show).toBe(true);
      expect(chart[family].splitLine.lineStyle.type).toBe('dashed');
      expect(chart[family].axisLine.show).toBe(false);
      expect(chart[family].axisTick.show).toBe(false);
    }

    expect(chart.timeAxis.splitLine.show).toBe(false);
    expect(chart.timeAxis.axisLine.show).not.toBe(false);
  });

  it('provides a ten-colour categorical series palette with no duplicates', () => {
    const chart = echartsTheme(createSmTheme('light'));

    expect(chart.color).toHaveLength(10);
    expect(new Set(chart.color).size).toBe(10);
  });
});
