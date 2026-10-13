/**
 * Pure map from the MUI theme to an ECharts theme object (DM-D13). No
 * feature imports `echarts` except ChartRenderer, and ChartRenderer imports
 * this, never the token layer — the theme is the single MUI→ECharts mapper.
 *
 * **Task 127 — the axis styling had to move under the four axis families.**
 * `axisLine` / `axisTick` / `axisLabel` / `splitLine` used to sit at the top
 * level of this object, where ECharts never reads them, so every chart drew
 * with stock ECharts axis colours in both light and dark mode. Verified
 * against the installed `echarts@6.1.0` source rather than recalled (`I2`):
 *
 * - `coord/axisModelCreator.js:69` — an axis model merges
 *   `themeModel.get(axisType + 'Axis')`, where `axisType` is one of
 *   `AXIS_TYPES` = `value` | `category` | `time` | `log`
 *   (`coord/axisCommonTypes.js:44-49`). So the readable keys are
 *   `valueAxis`, `categoryAxis`, `timeAxis`, `logAxis` — nothing else.
 * - `model/Global.js:691-710` (`mergeTheme`) merges every *other* theme key
 *   onto the root option, where a bare `axisLine` means nothing.
 * - ECharts' own bundled theme does exactly this: `theme/dark.js:47-74`
 *   defines one `axisCommon()` builder and `:226-229` spreads it across all
 *   four families. `axisCommon(theme)` below mirrors that shape.
 *
 * Merge order is `option` → theme → built-in defaults, each step
 * non-overwriting (`axisModelCreator.js:69-70`), so a per-chart option still
 * wins over this and this still wins over ECharts' defaults.
 *
 * Which lines are drawn is a reference decision, not an ECharts default:
 * `reference/app/charts.jsx` draws **horizontal gridlines only**. The y axis
 * is `value` or `log` (`ChartRenderer`'s `resolveScale`) and the x axis is
 * `time`, so value/log keep the dashed `splitLine` and drop their axis line
 * and ticks, while `time` keeps its axis line and has no split line.
 * `categoryAxis` is styled for completeness — no chart here uses one today,
 * but leaving one family unstyled is how this defect looked in the first
 * place.
 *
 * `color` is unchanged and stays top level (`mergeTheme` reads it there):
 * `WidgetFrame` and `ExploreResult` call `echartsTheme(muiTheme).color` for
 * `projectSeries`, and neither needed an edit for this task.
 */
import type { Theme } from '@mui/material/styles';

/** One axis family's styling — the shape `theme/dark.js`'s `axisCommon()` produces. */
export interface EChartsAxisTheme {
  axisLine: { show?: boolean; lineStyle: { color: string } };
  axisTick: { show?: boolean; lineStyle: { color: string } };
  axisLabel: { color: string; fontFamily: string };
  splitLine: { show?: boolean; lineStyle: { color: string; type: 'dashed' } };
}

export interface EChartsTheme {
  color: string[];
  backgroundColor: string;
  textStyle: { fontFamily: string; color: string };
  /** Read for a `type: 'time'` axis — `ChartRenderer`'s x axis. */
  timeAxis: EChartsAxisTheme;
  /** Read for a `type: 'value'` axis — `ChartRenderer`'s default y axis. */
  valueAxis: EChartsAxisTheme;
  /** Read for a `type: 'log'` axis — the y axis under `scale: 'log'`. */
  logAxis: EChartsAxisTheme;
  /** No chart uses one yet; styled so the family cannot fall back to stock colours. */
  categoryAxis: EChartsAxisTheme;
  tooltip: {
    backgroundColor: string;
    borderColor: string;
    textStyle: { color: string; fontFamily: string };
  };
  legend: { textStyle: { color: string; fontFamily: string } };
}

/**
 * The shared per-family builder, mirroring `theme/dark.js`'s `axisCommon()`.
 * Every colour comes from the same two theme slots — `palette.divider` for
 * lines, `palette.text.secondary` for labels — so all four families track
 * the mode together and none can drift.
 */
function axisCommon(theme: Theme, fontFamily: string): EChartsAxisTheme {
  const { palette } = theme;
  return {
    axisLine: { lineStyle: { color: palette.divider } },
    axisTick: { lineStyle: { color: palette.divider } },
    axisLabel: { color: palette.text.secondary, fontFamily },
    splitLine: { lineStyle: { color: palette.divider, type: 'dashed' } },
  };
}

export function echartsTheme(theme: Theme): EChartsTheme {
  const { palette, typography } = theme;
  // Theme.typography.fontFamily is optional in MUI's type even though
  // createSmTheme always sets it; fall back to the CSS default rather than
  // widening every field below to `string | undefined`.
  const fontFamily = typography.fontFamily ?? 'inherit';

  // A value/log axis carries the horizontal gridlines and nothing else —
  // the reference draws no y axis line and no y ticks, only the dashed
  // rules the labels sit against. Built fresh per call so no two families
  // share a mutable object.
  const measureAxis = (): EChartsAxisTheme => {
    const base = axisCommon(theme, fontFamily);
    return {
      ...base,
      axisLine: { ...base.axisLine, show: false },
      axisTick: { ...base.axisTick, show: false },
      splitLine: { ...base.splitLine, show: true },
    };
  };

  // The x axis: its own line is the plot's baseline, and it contributes no
  // vertical gridlines. `show: false` is stated rather than left to
  // ECharts' own `timeAxis` default (`coord/axisDefault.js:226-229` also
  // defaults it off) so the intent survives a dependency bump.
  const timeAxisBase = axisCommon(theme, fontFamily);
  const timeAxis: EChartsAxisTheme = {
    ...timeAxisBase,
    splitLine: { ...timeAxisBase.splitLine, show: false },
  };

  return {
    color: palette.chart.categorical,
    backgroundColor: 'transparent',
    textStyle: { fontFamily, color: palette.text.secondary },
    timeAxis,
    valueAxis: measureAxis(),
    logAxis: measureAxis(),
    categoryAxis: axisCommon(theme, fontFamily),
    tooltip: {
      backgroundColor: palette.background.paper,
      borderColor: palette.divider,
      textStyle: { color: palette.text.primary, fontFamily },
    },
    legend: { textStyle: { color: palette.text.secondary, fontFamily } },
  };
}
