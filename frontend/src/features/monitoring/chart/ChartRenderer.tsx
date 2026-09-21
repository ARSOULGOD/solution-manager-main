/**
 * The only module permitted to import `echarts` (`FE-D16`, task 009's
 * `no-restricted-imports` exemption names this exact file). Loaded via a
 * dynamic `import()` so it lands in its own async chunk, never the initial
 * bundle (design §17) — verified by `scripts/assert-echarts-chunk.mjs`,
 * chained onto `npm run build`.
 *
 * Renders a `ChartModel` (task 071's `projectSeries` output) as a
 * multi-series time-series line chart. Task 072 owned the plumbing: theme,
 * canvas renderer, progressive rendering, resize, dispose, reduced motion.
 * Task 073 layered gap breaks and shaded coverage bands, hollow
 * low-coverage markers, retention truncation, dimmed inactive/pending
 * lines, thresholds, replica-count-change annotations, and the restricted
 * log scale onto that same `EChartsOption` shape. Task 074 (this file, now)
 * adds the tooltip and legend (`dynamic-metrics` §12, UI §A.10.6) —
 * `buildOption()` still owns the whole `EChartsOption` shape.
 *
 * Task 075 closes the design's stated residual: a canvas chart is
 * otherwise unreachable by keyboard (§18). The `<div>` that hosts the
 * ECharts canvas is now the one focus stop it describes — `←`/`→` move a
 * cursor along the time axis, `↑`/`↓` change the focused series, `Enter`
 * opens `ChartDataTable` (task 075's own file), a real keyboard-reachable
 * table of the same `ChartModel`. A visually-hidden `aria-live` region
 * announces the focused series/instant/value as the cursor moves — the
 * chart itself carries no visible cursor indicator, since the pointed-at
 * value is exactly what the tooltip already shows a sighted mouse user;
 * this is the keyboard-only equivalent, not a second visual affordance.
 * `aria: { enabled: true }` is set on the option so ECharts' own reading
 * carries the same series names the legend uses (§18's last row). None of
 * this is a claim that the canvas itself became accessible — §18's own
 * text is explicit that a canvas chart "will still fail some
 * screen-reader chart-exploration heuristics"; the table is the
 * mitigation, stated honestly rather than papered over.
 *
 * The plan text asks for `large: true` above a point threshold; ECharts'
 * own type surface (`SeriesLargeOptionMixin`, verified against the
 * installed `echarts@6.1.0` types) only offers `large`/`largeThreshold` on
 * `candlestick`/`lines`/`scatter` series, not `line` — so this uses
 * `progressive`/`progressiveThreshold` instead, at ECharts' own verified
 * global defaults (`globalDefault.js`: `progressiveThreshold: 3000`,
 * `progressive: 400`) rather than inventing new numbers.
 *
 * Task 076 adds the `fill` prop — `DM-D12`'s "area is `options.fill: true`
 * on `mark: line`, not a fourth mark" — as `LineSeriesOption.areaStyle`.
 * `mark: 'gauge'`/`'single-stat'` are NOT rendered here: `GaugeMark.tsx`
 * and `SingleStatMark.tsx` (task 076) are plain SVG/MUI, deliberately
 * outside this file's one `echarts` import site — see their own headers.
 *
 * **Task 109 — two independent width thresholds.** Under `md` the legend
 * moves from the plot's right edge to below it, `orient: 'horizontal'`
 * instead of `'vertical'` — still `type: 'scroll'` (§12: "the legend
 * scrolls, it never truncates"), so a horizontal legend that overflows
 * paginates rather than wrapping into an unbounded number of rows. Under
 * `sm` the canvas is not rendered at all: `ChartDataTableContent` (task
 * 075's own tabular alternative, extracted from `ChartDataTable.tsx` by
 * this task so both call sites share one table) becomes the primary,
 * inline affordance — "a 320px canvas is not readable" (task spec) — in
 * place of a click/Enter-gated dialog. It renders inside an
 * `overflowY: 'auto'` box the same fixed height the canvas would have
 * occupied, so no ancestor's declared height needs to change for it (the
 * wrapping `Box` in `ExploreResult.tsx`/`WidgetFrame.tsx` is untouched).
 *
 * **Task 128 — legibility.** Four changes, all of them things the chart was
 * getting from an ECharts default rather than from a decision:
 *
 *   1. `grid` is stated on all four sides with `containLabel: true`
 *      (`GRID_INSET`). It previously set only `right`, leaving `top: 60` /
 *      `bottom: 60` / `left: '10%'` in force — ~120px of padding inside a
 *      220px widget body, i.e. roughly 100px of actual plot with the y
 *      labels overlapping it.
 *   2. The legend now defaults to **below** the plot (`legendPosition`),
 *      which is what `WidgetConfigV1.legend.position` has always declared
 *      and this file has always ignored while reserving 180px of width on
 *      the right. Explore's wide panel opts back into the vertical variant
 *      explicitly. Task 109's below-`md` override still wins over both.
 *   3. Axis polish: y ticks are bounded and derived from the height
 *      actually available (`ySplitNumber`; the reference uses 4), y labels
 *      go through `formatAxisValue` so a tick reads `47.9k` rather than
 *      `47,900 count`, x labels use `hideOverlap`, and the time axis is
 *      padded flush (`boundaryGap: [0, 0]` — a time axis takes a tuple;
 *      `false` is category-axis-only).
 *   4. "Open data table" moved off the plot. It was a text button
 *      absolutely positioned over the top-centre of the canvas — on top of
 *      the data it offers an alternative to — and is now an icon button
 *      pinned bottom-right, with the same accessible name.
 */
import { Box } from '@mui/material';
import type { Theme } from '@mui/material/styles';
import { useTheme } from '@mui/material/styles';
import useMediaQuery from '@mui/material/useMediaQuery';
import type { ECharts, EChartsOption, LineSeriesOption } from 'echarts';
import type { KeyboardEvent } from 'react';
import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react';

import type { ChartModel, ChartSeries, ChartThreshold } from '@contracts/metrics/types';
import { formatAxisTimestamp, formatAxisValue, formatUnitValue } from '@shared/format';
import { t } from '@shared/i18n';
import { echartsTheme } from '@shared/theme';
import { IconButton } from '@shared/ui';

import { ChartDataTable, ChartDataTableContent } from './ChartDataTable';
import { ALL_STATE_TAGS, GAP_TAG, STATE_TAG, type StateTag } from './chartStateTags';
import { TableIcon } from './icons';
import { useEcharts } from './useEcharts';

// Present for screen readers, invisible on screen — the same clip pattern
// `LogViewer.tsx`'s own live region uses (task 067).
const visuallyHiddenSx = {
  position: 'absolute',
  width: 1,
  height: 1,
  padding: 0,
  margin: -1,
  overflow: 'hidden',
  clip: 'rect(0, 0, 0, 0)',
  whiteSpace: 'nowrap',
  border: 0,
} as const;

const PROGRESSIVE_POINT_THRESHOLD = 3000;
const PROGRESSIVE_CHUNK_SIZE = 400;

/** A.10.5: "Inactive-replica and pending-config series render dashed and at reduced opacity." */
const DIMMED_LINE_STATES: ReadonlySet<ChartSeries['state']> = new Set(['inactive-replica', 'pending-config-change']);
/** Not specified numerically anywhere in the design — a dim-but-legible interpretation of "reduced opacity". */
const DIMMED_LINE_OPACITY = 0.6;
const LOW_COVERAGE_SYMBOL_SIZE = 6;

// `StateTag`/`GAP_TAG`/`STATE_TAG`/`ALL_STATE_TAGS` moved to `./chartStateTags.ts`
// (task 075) — `ChartDataTable` needs the same state-label vocabulary and
// importing it from here would be circular the other way.

/** §5's one concrete example (`options.legend.maxHeightRows: 4`) — reused rather than a fresh guess. */
const DEFAULT_LEGEND_MAX_HEIGHT_ROWS = 4;
/** Not specified anywhere in the design — an interpretation of one legend row's pixel height. */
const LEGEND_ROW_HEIGHT = 22;
/** Width reserved on the chart's right edge so the vertical legend never overlaps the plot. */
const LEGEND_RESERVED_WIDTH = 180;
/** Height reserved at the chart's bottom edge for the horizontal legend below `md` (task 109) — one scrollable row, not `legendMaxHeightRows` of them, since a horizontal `type: 'scroll'` legend paginates sideways rather than growing downward. */
const LEGEND_RESERVED_HEIGHT = 36;

/**
 * Task 128 — the plot box, stated on all four sides. Before this, `grid`
 * set only `right` (or `right` + `bottom`), leaving ECharts' own defaults
 * (`top: 60`, `bottom: 60`, `left: '10%'`, `containLabel: false`) in place
 * on the others: 120px of vertical padding inside a 220px widget body left
 * roughly 100px of actual plot, with the y labels overlapping it.
 *
 * `containLabel: true` makes these insets bound the labels too, so the
 * numbers stay small and no label is ever clipped. It is honoured by the
 * full `echarts` package this file imports — `node_modules/echarts/index.js:261`
 * calls `use(LegacyGridContainLabel)` (verified in source, `I2`); only a
 * tree-shaken `echarts/core` build would have to register it itself.
 */
const GRID_INSET = { top: 12, left: 8, right: 12, bottom: 8 } as const;

/** The reference's own y-tick count (`charts.jsx`, `const yticks = 4`) — used when the height is unknown. */
const DEFAULT_Y_SPLIT_NUMBER = 4;
/** Roughly one tick per this many pixels of plot, bounded — a 150px widget body should not carry six labels. */
const PX_PER_Y_TICK = 48;
const MIN_Y_SPLIT_NUMBER = 2;
const MAX_Y_SPLIT_NUMBER = 6;

/**
 * Bucketed deliberately: `option` is memoized, and threading a raw pixel
 * height through would rebuild it on every frame of a drag-resize. The
 * tick count only changes when the bucket does.
 */
function ySplitNumber(heightPx: number | null): number {
  if (heightPx === null || heightPx <= 0) {
    return DEFAULT_Y_SPLIT_NUMBER;
  }
  const derived = Math.round(heightPx / PX_PER_Y_TICK);
  return Math.min(MAX_Y_SPLIT_NUMBER, Math.max(MIN_Y_SPLIT_NUMBER, derived));
}

export type ChartScale = 'linear' | 'log';
export type LegendPosition = 'bottom' | 'right';

export interface ChartRendererProps {
  model: ChartModel;
  ariaLabel?: string;
  /** Rendered as `markLine`, both colour and dash pattern (§12, §18 — never colour alone). */
  thresholds?: readonly ChartThreshold[];
  /** Defaults to `'linear'`; `'log'` is restricted — see `resolveScale`. */
  scale?: ChartScale;
  /** §12: "the legend scrolls, never truncates" — this bounds height only. Defaults to 4 (§5's example). */
  legendMaxHeightRows?: number;
  /**
   * Where the legend sits relative to the plot. Defaults to `'bottom'`,
   * matching the reference (`charts.jsx`'s `Legend` renders under the
   * chart) and `WidgetConfigV1.legend.position`, whose only value is
   * `'bottom'` and which used to be ignored here while 180px of width was
   * reserved on the right instead. `'right'` stays available for Explore's
   * wide result panel. Below `md` this is forced to `'bottom'` regardless
   * (task 109).
   */
  legendPosition?: LegendPosition;
  /** "Area" is this flag, not a separate mark (`DM-D12`) — styling only, changes nothing about what is queried. Defaults to `false`. */
  fill?: boolean;
}

export interface ScaleResolution {
  type: 'value' | 'log';
  /** Present only when `'log'` was requested but is not honoured. */
  disabledReason?: string;
}

/**
 * §12: "Log scale is disabled for counters under `transform: rate`" — a
 * counter rate is legitimately zero when idle, and a log axis silently
 * drops every non-positive point. Every other case (gauges, and counters
 * rendered as `delta`) leaves log scale available; `buildLineSeries` below
 * excludes non-positive values with a visible notice rather than letting
 * ECharts drop them silently.
 *
 * Exported so a future control (the toolbar's scale toggle has no task yet)
 * can disable itself with the same stated reason instead of duplicating
 * this rule.
 */
export function resolveScale(model: ChartModel, requestedScale: ChartScale): ScaleResolution {
  if (requestedScale !== 'log') {
    return { type: 'value' };
  }
  if (model.metric.metricType === 'counter' && model.render.transform === 'rate') {
    return {
      type: 'value',
      disabledReason:
        'Log scale is unavailable for counter rates — a rate is legitimately zero when idle, and a log axis would hide every zero point.',
    };
  }
  return { type: 'log' };
}

/** The one literal example the design gives (§4.4, A.10.5) — reproduced exactly, generalised only over the sign. */
function formatReplicaCountChangeLabel(delta: number): string {
  const verb = delta > 0 ? 'added' : 'removed';
  const sign = delta > 0 ? '+' : '';
  return `replica ${verb} (${sign}${delta}) — summed series steps; not a workload change`;
}

type MarkAreaPair = [{ xAxis: string; name?: string; itemStyle?: Record<string, unknown> }, { xAxis: string }];

type LineDataEntry =
  | [string, number]
  | { value: [string, number | null]; symbol: string; itemStyle?: Record<string, unknown> };

interface PendingEntry {
  ts: string;
  /** A gap-boundary marker must never win a same-timestamp collision against a real point (obligation 1). */
  isNullMarker: boolean;
  echartsEntry: LineDataEntry;
}

/**
 * Merges real points, low-coverage overrides and gap-boundary `null`
 * markers into one time-sorted array. `connectNulls: false` (set on the
 * series below) makes ECharts break the line at any `null` value — feeding
 * one at each gap's `from`/`to` is the mechanism §12's "fed as absent" and
 * `DM-D16` both describe; the segment on *either* side of a `null` point is
 * skipped, so a single boundary marker is enough to break the whole span,
 * but both edges are fed for a visually precise break rather than relying
 * on that as an implementation detail.
 *
 * `excludeNonPositive` (the log-scale gauge case) nulls out a point rather
 * than dropping it from the array, for the same reason: a silently missing
 * point is indistinguishable from a gap, and §12 requires that exclusion be
 * visible, not silent.
 */
function buildSeriesData(chartSeries: ChartSeries, seriesColor: string, excludeNonPositive: boolean): LineDataEntry[] {
  const pending: PendingEntry[] = [];

  for (const point of chartSeries.points) {
    const excluded = excludeNonPositive && point.value <= 0;
    if (point.lowCoverage || excluded) {
      pending.push({
        ts: point.ts,
        isNullMarker: false,
        echartsEntry: {
          value: [point.ts, excluded ? null : point.value],
          symbol: excluded ? 'none' : 'circle',
          symbolSize: LOW_COVERAGE_SYMBOL_SIZE,
          ...(excluded ? {} : { itemStyle: { color: 'transparent', borderColor: seriesColor, borderWidth: 1.5 } }),
        } as LineDataEntry,
      });
    } else {
      pending.push({ ts: point.ts, isNullMarker: false, echartsEntry: [point.ts, point.value] });
    }
  }

  for (const gap of chartSeries.gaps) {
    pending.push({ ts: gap.from, isNullMarker: true, echartsEntry: { value: [gap.from, null], symbol: 'none' } });
    pending.push({ ts: gap.to, isNullMarker: true, echartsEntry: { value: [gap.to, null], symbol: 'none' } });
  }

  const byTs = new Map<string, PendingEntry>();
  for (const entry of pending) {
    const existing = byTs.get(entry.ts);
    if (existing !== undefined && entry.isNullMarker && !existing.isNullMarker) {
      continue;
    }
    byTs.set(entry.ts, entry);
  }

  return Array.from(byTs.keys())
    .sort()
    .map((ts) => {
      const entry = byTs.get(ts);
      if (entry === undefined) {
        throw new Error('buildSeriesData: unreachable — key came from this same map');
      }
      return entry.echartsEntry;
    });
}

function countExcludedNonPositive(model: ChartModel, excludeNonPositive: boolean): number {
  if (!excludeNonPositive) {
    return 0;
  }
  return model.series.reduce((sum, s) => sum + s.points.filter((p) => p.value <= 0).length, 0);
}

function buildLineSeries(
  model: ChartModel,
  muiTheme: Theme,
  prefersReducedMotion: boolean,
  thresholds: readonly ChartThreshold[],
  yAxisType: 'value' | 'log',
  fill: boolean,
): LineSeriesOption[] {
  const totalPoints = model.series.reduce((sum, series) => sum + series.points.length, 0);
  const useProgressive = !prefersReducedMotion && totalPoints > PROGRESSIVE_POINT_THRESHOLD;
  const excludeNonPositive = yAxisType === 'log';

  return model.series.map((chartSeries, index) => {
    const isFirst = index === 0;
    const dimmed = DIMMED_LINE_STATES.has(chartSeries.state);

    // Gaps are per-series (each series has its own absence); thresholds and
    // the retention band/boundary are chart-wide facts and only need to be
    // attached once, so they ride on series[0] — ECharts draws a series's
    // markArea/markLine across the whole coordinate system, not clipped to
    // that series's own line.
    const gapAreas: MarkAreaPair[] = chartSeries.gaps.map((gap) => [
      { xAxis: gap.from, name: gap.reason, itemStyle: { color: muiTheme.palette.action.hover } },
      { xAxis: gap.to },
    ]);
    const retentionArea: MarkAreaPair[] =
      isFirst && model.coverage.truncatedBand !== undefined
        ? [
            [
              {
                xAxis: model.coverage.truncatedBand.from,
                name: 'retention-truncated',
                itemStyle: { color: muiTheme.palette.tone.warn.bg },
              },
              { xAxis: model.coverage.truncatedBand.to },
            ],
          ]
        : [];

    const thresholdLines = isFirst
      ? thresholds.map((threshold) => ({
          yAxis: threshold.value,
          name: threshold.label,
          lineStyle: {
            color: threshold.severity === 'critical' ? muiTheme.palette.tone.err.main : muiTheme.palette.tone.warn.main,
            type: threshold.style ?? 'dashed',
          },
          label: { formatter: threshold.label, fontSize: 10 },
        }))
      : [];
    const retentionBoundaryLine =
      isFirst && model.coverage.truncatedByRetention
        ? [
            {
              xAxis: model.coverage.servedFrom,
              name: 'retention-boundary',
              lineStyle: { color: muiTheme.palette.tone.warn.main, type: 'dashed' as const },
              label: { formatter: 'Data before this point is outside the retention window', fontSize: 10 },
            },
          ]
        : [];
    const annotationLines = (chartSeries.replicaCountChanges ?? []).map((change) => ({
      xAxis: change.ts,
      name: formatReplicaCountChangeLabel(change.delta),
      // A caret at the top end only — `[bottom, top]` per ECharts' two-end symbol convention for a 1D markLine.
      symbol: ['none', 'triangle'] as [string, string],
      symbolSize: [0, 8] as [number, number],
      lineStyle: { color: muiTheme.palette.divider, type: 'dashed' as const },
      label: { formatter: formatReplicaCountChangeLabel(change.delta), fontSize: 10 },
    }));

    const markAreaData = [...gapAreas, ...retentionArea];
    const markLineData = [...thresholdLines, ...retentionBoundaryLine, ...annotationLines];

    const lineSeries: LineSeriesOption = {
      type: 'line',
      name: chartSeries.label,
      data: buildSeriesData(chartSeries, chartSeries.color, excludeNonPositive),
      connectNulls: false,
      showSymbol: true,
      symbol: chartSeries.points.length === 1 ? 'circle' : 'none',
      ...(chartSeries.points.length === 1 ? { symbolSize: 6 } : {}),
      lineStyle: {
        color: chartSeries.color,
        type: dimmed || chartSeries.dashPattern === 'dashed' ? 'dashed' : 'solid',
        ...(dimmed ? { opacity: DIMMED_LINE_OPACITY } : {}),
      },
      itemStyle: { color: chartSeries.color },
      // `DM-D12`: "area" is `options.fill: true` on `mark: line`, not a
      // separate mark — `areaStyle` is ECharts' own mechanism for exactly
      // that, added here without touching any of the gap/threshold/dash
      // logic above it, none of which changes when fill does.
      ...(fill ? { areaStyle: {} } : {}),
      ...(markAreaData.length > 0 ? { markArea: { silent: true, data: markAreaData } } : {}),
      ...(markLineData.length > 0 ? { markLine: { silent: true, symbol: 'none', data: markLineData } } : {}),
      ...(useProgressive
        ? { progressive: PROGRESSIVE_CHUNK_SIZE, progressiveThreshold: PROGRESSIVE_POINT_THRESHOLD }
        : {}),
    };

    return lineSeries;
  });
}

// ---------------------------------------------------------------------------
// FE-074 — tooltip and legend (`dynamic-metrics` §12, UI §A.10.6).
// ---------------------------------------------------------------------------

interface TooltipRow {
  label: string;
  color: string;
  dashed: boolean;
  tag?: StateTag;
  /** `undefined` — a gap row — renders the literal word "gap", never blank, never zero. */
  valueText?: string;
  /** Sort key only; gap rows sort last regardless of any series' last real value. */
  sortValue: number;
}

/**
 * Per series: a point at the hovered instant, looked up by exact timestamp
 * against `ChartSeries.points` — the pure domain array, so a synthetic
 * gap-boundary `null` marker (`buildSeriesData`'s own construct, never part
 * of `ChartModel`) can never masquerade as a real point here. `axisValueMs`
 * is always a real point's own timestamp (some series' nearest match to the
 * raw cursor position — verified against `axisTrigger.js`'s
 * `buildPayloadsBySeries`, which always sets `snapToValue` to one series'
 * actual data value), so every other series is checked against a real
 * instant, not an arbitrary pixel position — `Date.parse` on an ISO string
 * with an explicit offset (`Z` in every fixture and every projected point)
 * always agrees with ECharts' own `parseDate` (verified against
 * `util/number.js`: both resolve via `Date.UTC` for an explicit-offset
 * string), so no epsilon/rounding tolerance is needed for the comparison.
 *
 * ECharts' own axis-trigger `params` are deliberately not used here — its
 * `payloadBatch` only carries series tied for the *globally* closest match
 * (`axisTrigger.js`'s `dist <= minDist` gate), silently dropping every
 * other series from the callback. That is exactly the "blank, not `gap`"
 * failure §12 rules out, so the row list is built from `model.series`
 * directly instead.
 */
function buildTooltipRows(model: ChartModel, axisValueMs: number): TooltipRow[] {
  const rows = model.series.map((chartSeries): TooltipRow => {
    const dashed = chartSeries.dashPattern === 'dashed' || DIMMED_LINE_STATES.has(chartSeries.state);
    const pointAtCursor = chartSeries.points.find((p) => Date.parse(p.ts) === axisValueMs);
    if (pointAtCursor === undefined) {
      return { label: chartSeries.label, color: chartSeries.color, dashed, tag: GAP_TAG, sortValue: -Infinity };
    }
    const tag = STATE_TAG[chartSeries.state];
    return {
      label: chartSeries.label,
      color: chartSeries.color,
      dashed,
      ...(tag !== undefined ? { tag } : {}),
      valueText: formatUnitValue(pointAtCursor.value, model.metric.unit),
      sortValue: pointAtCursor.value,
    };
  });
  // §12 / A.10.6: "sorted by value" — descending (largest first); gap rows
  // (no value to compare) always sort last, via `sortValue: -Infinity`.
  return rows.sort((a, b) => b.sortValue - a.sortValue);
}

function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function renderTagHtml(tag: StateTag, muiTheme: Theme): string {
  const { bg, main } = muiTheme.palette.tone[tag.tone];
  return `<span style="margin-left:6px;padding:1px 4px;border-radius:3px;font-size:10px;background:${bg};color:${main};">${escapeHtml(tag.label)}</span>`;
}

function renderTooltipRowHtml(row: TooltipRow, muiTheme: Theme): string {
  const swatch = row.dashed
    ? `border:1.5px dashed ${row.color};background:transparent;`
    : `background:${row.color};border:1.5px solid ${row.color};`;
  const value =
    row.valueText ?? `<span style="color:${muiTheme.palette.text.disabled}">gap</span>`;
  return (
    '<div style="display:flex;align-items:center;justify-content:space-between;gap:16px;padding:2px 0;">' +
    `<span style="display:flex;align-items:center;"><span style="display:inline-block;width:9px;height:9px;border-radius:2px;margin-right:6px;${swatch}"></span>` +
    escapeHtml(row.label) +
    (row.tag !== undefined ? renderTagHtml(row.tag, muiTheme) : '') +
    '</span>' +
    `<span>${value}</span>` +
    '</div>'
  );
}

function renderTooltipHtml(headerLabel: string, rows: readonly TooltipRow[], muiTheme: Theme): string {
  const header = `<div style="font-weight:600;margin-bottom:4px;">${escapeHtml(headerLabel)}</div>`;
  return `<div>${header}${rows.map((row) => renderTooltipRowHtml(row, muiTheme)).join('')}</div>`;
}

/**
 * ECharts axis-trigger `formatter` params are typed as bare
 * `CallbackDataParams` in the public surface, but carry `axisValue` at
 * runtime for an axis trigger (verified against `TooltipView.d.ts`'s
 * internal `TooltipCallbackDataParams`, which the public type doesn't
 * re-export) — narrowed here rather than widening the public import.
 */
function tooltipFormatter(model: ChartModel, muiTheme: Theme, rawParams: unknown): string {
  const params = Array.isArray(rawParams) ? rawParams : [rawParams];
  const first = params[0] as { axisValue?: unknown } | undefined;
  if (typeof first?.axisValue !== 'number') {
    return '';
  }
  const axisValueMs = first.axisValue;
  const rows = buildTooltipRows(model, axisValueMs);
  const header = formatAxisTimestamp(new Date(axisValueMs).toISOString());
  return renderTooltipHtml(header, rows, muiTheme);
}

/** The slice of ECharts' `TextCommonOption` a rich-text token needs — kept local rather than importing the (unexported-at-root) echarts type. */
interface RichTagStyle {
  backgroundColor: string;
  color: string;
  fontSize: number;
  padding: [number, number];
  borderRadius: number;
}

/** Legend rich-text tokens, one per `StateTag`, styled from the same `tone` mapping the tooltip uses. */
function legendRichStyles(muiTheme: Theme): Record<string, RichTagStyle> {
  const rich: Record<string, RichTagStyle> = {};
  for (const tag of ALL_STATE_TAGS) {
    const { bg, main } = muiTheme.palette.tone[tag.tone];
    rich[tag.token] = { backgroundColor: bg, color: main, fontSize: 10, padding: [1, 4], borderRadius: 3 };
  }
  return rich;
}

/** Every plotted series gets an entry (`legend.data`, built separately) — `ok` just renders no tag suffix. */
function legendFormatter(model: ChartModel, name: string): string {
  const chartSeries = model.series.find((s) => s.label === name);
  const tag = chartSeries === undefined ? undefined : STATE_TAG[chartSeries.state];
  return tag === undefined ? name : `${name} {${tag.token}|${tag.label}}`;
}

function buildOption(
  model: ChartModel,
  muiTheme: Theme,
  prefersReducedMotion: boolean,
  thresholds: readonly ChartThreshold[],
  requestedScale: ChartScale,
  legendMaxHeightRows: number,
  fill: boolean,
  legendPosition: LegendPosition,
  ySplitCount: number,
): EChartsOption {
  const { type: yAxisType } = resolveScale(model, requestedScale);
  const series = buildLineSeries(model, muiTheme, prefersReducedMotion, thresholds, yAxisType, fill);
  const excludedCount = countExcludedNonPositive(model, yAxisType === 'log');
  const legendBelow = legendPosition === 'bottom';

  return {
    animation: !prefersReducedMotion,
    // §18's last accessibility row: "ECharts `aria` enabled, with series
    // names supplied from the same labels the legend uses" — `name` below
    // is `chartSeries.label`, the same string `legend.data` is built from.
    aria: { enabled: true },
    xAxis: {
      type: 'time',
      // A time axis takes `[start, end]` padding, never a boolean —
      // `boundaryGap: false` is category-axis-only
      // (`echarts/types/dist/shared.d.ts:2676-2679`, `:2716`). `[0, 0]`
      // is the same intent: the line starts and ends flush with the plot.
      boundaryGap: [0, 0],
      // The reference draws six x labels and never lets them collide;
      // ECharts' equivalent is to drop a label rather than overprint it.
      axisLabel: { hideOverlap: true },
    },
    yAxis: {
      type: yAxisType,
      // Reference: `const yticks = 4`, scaled here to the height actually
      // available so a 150px widget body does not carry six labels.
      splitNumber: ySplitCount,
      // Compact tick labels — `47.9k`, not `47,900 count`. The unit lives
      // in the widget title, not on every tick; see `formatAxisValue`.
      axisLabel: { formatter: (value: number) => formatAxisValue(value, model.metric.unit) },
    },
    // The plot box, stated on all four sides (see `GRID_INSET`), plus the
    // reservation for whichever edge the legend occupies.
    grid: legendBelow
      ? { ...GRID_INSET, bottom: GRID_INSET.bottom + LEGEND_RESERVED_HEIGHT, containLabel: true }
      : { ...GRID_INSET, right: GRID_INSET.right + LEGEND_RESERVED_WIDTH, containLabel: true },
    tooltip: {
      trigger: 'axis',
      formatter: (params: unknown) => tooltipFormatter(model, muiTheme, params),
    },
    legend: legendBelow
      ? {
          type: 'scroll',
          orient: 'horizontal',
          left: 'center',
          bottom: 0,
          // Every plotted series, unconditionally — §12: "the legend scrolls,
          // it never truncates". A horizontal `type: 'scroll'` legend
          // paginates sideways (arrows) rather than wrapping into more rows.
          data: model.series.map((chartSeries) => chartSeries.label),
          formatter: (name: string) => legendFormatter(model, name),
          textStyle: { rich: legendRichStyles(muiTheme) },
        }
      : {
          type: 'scroll',
          orient: 'vertical',
          right: 8,
          top: 'middle',
          height: legendMaxHeightRows * LEGEND_ROW_HEIGHT,
          // Every plotted series, unconditionally — §12: "the legend scrolls,
          // it never truncates". Only `height` bounds what's visible at once.
          data: model.series.map((chartSeries) => chartSeries.label),
          formatter: (name: string) => legendFormatter(model, name),
          textStyle: { rich: legendRichStyles(muiTheme) },
        },
    series,
    ...(excludedCount > 0
      ? {
          graphic: [
            {
              type: 'text',
              right: 8,
              top: 4,
              style: {
                text: `${excludedCount} non-positive ${excludedCount === 1 ? 'value' : 'values'} excluded from the log scale`,
                fill: muiTheme.palette.text.secondary,
                fontSize: 11,
              },
            },
          ],
        }
      : {}),
  };
}

/** The union of every series' point timestamps, time-sorted — `←`/`→`'s "time axis" (`dynamic-metrics` §18). ISO-8601 UTC strings sort lexically in chronological order (`ChartRenderer.tsx`'s own tooltip already relies on this). */
function chartTimestamps(model: ChartModel): string[] {
  const unique = new Set(model.series.flatMap((chartSeries) => chartSeries.points.map((point) => point.ts)));
  return Array.from(unique).sort();
}

function clampIndex(index: number, length: number): number {
  if (length === 0) {
    return 0;
  }
  return Math.min(Math.max(index, 0), length - 1);
}

function cursorAnnouncement(model: ChartModel, timestamps: readonly string[], cursorIndex: number, seriesIndex: number): string {
  const chartSeries = model.series[clampIndex(seriesIndex, model.series.length)];
  const ts = timestamps[clampIndex(cursorIndex, timestamps.length)];
  if (chartSeries === undefined || ts === undefined) {
    return '';
  }
  const point = chartSeries.points.find((p) => p.ts === ts);
  const value = point === undefined ? t('chartCursorGap') : formatUnitValue(point.value, model.metric.unit);
  return t('chartCursorAnnouncement', { series: chartSeries.label, timestamp: formatAxisTimestamp(ts), value });
}

export function ChartRenderer({
  model,
  ariaLabel,
  thresholds = [],
  scale = 'linear',
  legendMaxHeightRows = DEFAULT_LEGEND_MAX_HEIGHT_ROWS,
  legendPosition = 'bottom',
  fill = false,
}: ChartRendererProps) {
  const muiTheme = useTheme();
  const prefersReducedMotion = useMediaQuery('(prefers-reduced-motion: reduce)');
  // Below `md` the legend moves under the plot; below `sm` the canvas
  // itself is replaced by the inline table (see this file's header).
  // Below `md` the legend is forced below the plot whatever the caller
  // asked for (task 109); at `md` and up the caller's own `legendPosition`
  // stands. `legendBelow` is now the default, so this narrows what used to
  // be the only way to get a horizontal legend rather than owning it.
  const compactLegend = useMediaQuery(muiTheme.breakpoints.down('md'));
  const effectiveLegendPosition: LegendPosition = compactLegend ? 'bottom' : legendPosition;
  const primaryTable = useMediaQuery(muiTheme.breakpoints.down('sm'));
  const containerRef = useRef<HTMLDivElement>(null);
  const hintId = useId();

  // Drives `yAxis.splitNumber`. Bucketed inside `ySplitNumber`, so this
  // rebuilds `option` only when the tick count genuinely changes, not on
  // every frame of a resize.
  const [plotHeightPx, setPlotHeightPx] = useState<number | null>(null);

  const [cursorIndex, setCursorIndex] = useState(0);
  const [focusedSeriesIndex, setFocusedSeriesIndex] = useState(0);
  const [tableOpen, setTableOpen] = useState(false);
  const timestamps = useMemo(() => chartTimestamps(model), [model]);

  // Keyed on `muiTheme` — ECharts themes are init-time-only, so a mode
  // change needs a real dispose+recreate (`useEcharts` does this whenever
  // this factory's identity changes). A data-only rerender leaves the
  // factory identity stable; see the `option` effect below instead.
  const createInstance = useCallback(
    async (container: HTMLDivElement): Promise<ECharts> => {
      const echarts = await import('echarts');
      return echarts.init(container, echartsTheme(muiTheme), { renderer: 'canvas' });
    },
    [muiTheme],
  );

  const chart = useEcharts(containerRef, createInstance);

  useEffect(() => {
    const container = containerRef.current;
    if (container === null) {
      return;
    }
    setPlotHeightPx(container.clientHeight);
    const observer = new ResizeObserver((entries) => {
      const entry = entries[0];
      if (entry !== undefined) {
        setPlotHeightPx(entry.contentRect.height);
      }
    });
    observer.observe(container);
    return () => observer.disconnect();
  }, []);

  // Resolved here rather than inside `buildOption` so the memo depends on
  // the bucketed tick count, not the raw pixel height — otherwise every
  // frame of a drag-resize would rebuild an identical option object.
  const ySplitCount = ySplitNumber(plotHeightPx);
  const option = useMemo(
    () =>
      buildOption(
        model,
        muiTheme,
        prefersReducedMotion,
        thresholds,
        scale,
        legendMaxHeightRows,
        fill,
        effectiveLegendPosition,
        ySplitCount,
      ),
    [
      model,
      muiTheme,
      prefersReducedMotion,
      thresholds,
      scale,
      legendMaxHeightRows,
      fill,
      effectiveLegendPosition,
      ySplitCount,
    ],
  );

  useEffect(() => {
    chart?.setOption(option, { notMerge: true });
  }, [chart, option]);

  const handleKeyDown = useCallback(
    (event: KeyboardEvent<HTMLDivElement>) => {
      switch (event.key) {
        case 'ArrowRight':
          event.preventDefault();
          setCursorIndex((index) => clampIndex(index + 1, timestamps.length));
          break;
        case 'ArrowLeft':
          event.preventDefault();
          setCursorIndex((index) => clampIndex(index - 1, timestamps.length));
          break;
        case 'ArrowDown':
          event.preventDefault();
          setFocusedSeriesIndex((index) => clampIndex(index + 1, model.series.length));
          break;
        case 'ArrowUp':
          event.preventDefault();
          setFocusedSeriesIndex((index) => clampIndex(index - 1, model.series.length));
          break;
        case 'Enter':
          event.preventDefault();
          setTableOpen(true);
          break;
        default:
          break;
      }
    },
    [model.series.length, timestamps.length],
  );

  // Falls back to the metric name so `role="application"` never ends up
  // with no accessible name — `ChartDataTable`'s own title uses the same
  // fallback (`ChartDataTable.tsx`'s `title` prop doc).
  const tableTitle = ariaLabel ?? model.metric.name;

  return (
    <Box sx={{ position: 'relative', width: '100%', height: '100%' }}>
      {/* Kept mounted (never conditionally unmounted) even while hidden
          below `sm` — `useEcharts`'s create/dispose effect keys on
          `containerRef`/`createInstance` identity, neither of which
          changes when `primaryTable` toggles, so unmounting this `div`
          on a live resize would strand the chart instance against a
          removed node with nothing to re-create it against. Toggling
          `display` instead leaves the ref, the instance and the
          `ResizeObserver` all intact; the observer picks the real size
          back up the moment the box becomes visible again. */}
      <Box sx={{ display: primaryTable ? 'none' : 'block', width: '100%', height: '100%', position: 'relative' }}>
        <div
          ref={containerRef}
          tabIndex={0}
          role="application"
          aria-label={tableTitle}
          aria-describedby={hintId}
          onKeyDown={handleKeyDown}
          style={{ width: '100%', height: '100%' }}
        />
        <Box id={hintId} sx={visuallyHiddenSx}>
          {t('chartKeyboardHint')}
        </Box>
        <Box aria-live="polite" aria-atomic="true" sx={visuallyHiddenSx}>
          {cursorAnnouncement(model, timestamps, cursorIndex, focusedSeriesIndex)}
        </Box>
        {/* Task 128 — out of the plot. This used to be a text button
            absolutely positioned over the TOP-CENTRE of the canvas, i.e.
            on top of the data it was offering an alternative to. It is now
            an icon button pinned bottom-right, clear of both the plot and
            the legend. `IconButton` requires `aria-label`, so the same
            accessible name survives verbatim and
            `ChartRenderer.a11y.test.tsx` passes unchanged; the `Enter`-key
            path in `handleKeyDown` is untouched and remains the primary
            keyboard route.

            Deliberately NOT wrapped in `shared/ui`'s `Tooltip`: that
            component exists to name *nameless* content and so wraps its
            child in a `role="button" tabIndex={0}` span, which would nest
            two interactive controls — axe's `nested-interactive`, caught
            by the a11y test here rather than assumed. The `aria-label`
            is the name; the icon needs no second one. */}
        <Box sx={{ position: 'absolute', bottom: 0, right: 0 }}>
          <IconButton size="sm" aria-label={t('chartDataTableButtonLabel')} onClick={() => setTableOpen(true)}>
            <TableIcon fontSize="small" />
          </IconButton>
        </Box>
        <ChartDataTable model={model} open={tableOpen} onClose={() => setTableOpen(false)} title={tableTitle} />
      </Box>
      {/* Below `sm`: the tabular alternative IS the chart, offered
          directly rather than behind `Enter`/the button above (task 109,
          design §11/§18 — "a 320px canvas is not readable"). Scrolls
          within the same footprint the canvas would have occupied rather
          than growing it, so no caller's fixed-height wrapper needs to
          change for this (see this file's header). */}
      {primaryTable && (
        // `tabIndex`: task 110's axe pass — same reasoning as
        // `InstanceSelector.tsx`'s scrollable instance list. A vertically
        // scrollable region needs to be focusable itself for Safari's
        // keyboard scrolling (`scrollable-region-focusable`), regardless
        // of which axis scrolls or that its rows are already focusable.
        <Box
          tabIndex={0}
          sx={{ width: '100%', height: '100%', overflowY: 'auto', overflowX: 'hidden' }}
        >
          <ChartDataTableContent model={model} />
        </Box>
      )}
    </Box>
  );
}
