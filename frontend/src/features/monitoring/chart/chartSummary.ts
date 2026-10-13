/**
 * FE-075's pure half — `ChartDataTable`'s text summary (design §18: "a
 * text summary: metric, range, series count, min/max/last per series")
 * computed once here, formatted by the component. No `t()`, no `Intl`
 * formatting: `dynamic-metrics` design §18's i18n rule is "no concatenated
 * sentences", which means formatting belongs at the `t()` call site, not
 * baked into numbers here.
 *
 * `rangeFrom` is `coverage.servedFrom` (obligation 6's own served-from
 * timestamp), not the earliest rendered point — a series can be entirely
 * gapped at the start of the window, and the served range is the fact the
 * server declared, not one re-derived from whichever points happened to
 * land. `rangeTo` is the latest point actually rendered, across every
 * series, since the server declares no equivalent "servedTo".
 */
import type { ChartModel, SeriesState } from '@contracts/metrics/types';

export interface SeriesSummary {
  label: string;
  state: SeriesState;
  pointCount: number;
  /** `null` when the series has no points — never `0`, which would misreport an all-gap series as flat-zero. */
  min: number | null;
  max: number | null;
  last: number | null;
}

export interface ChartSummary {
  metricName: string;
  unit: string | null;
  /** `null` only when no series has a single rendered point anywhere. */
  rangeFrom: string | null;
  rangeTo: string | null;
  seriesCount: number;
  series: readonly SeriesSummary[];
}

function minMaxLast(values: readonly number[]): Pick<SeriesSummary, 'min' | 'max' | 'last'> {
  if (values.length === 0) {
    return { min: null, max: null, last: null };
  }
  let min = values[0] as number;
  let max = values[0] as number;
  for (const value of values) {
    if (value < min) min = value;
    if (value > max) max = value;
  }
  return { min, max, last: values[values.length - 1] as number };
}

function summarizeSeries(chartSeries: ChartModel['series'][number]): SeriesSummary {
  const values = chartSeries.points.map((point) => point.value);
  return {
    label: chartSeries.label,
    state: chartSeries.state,
    pointCount: chartSeries.points.length,
    ...minMaxLast(values),
  };
}

/** ISO-8601 UTC timestamps (every fixture, every projected point — `ChartRenderer.tsx`'s own tooltip verified this) sort lexically in chronological order, so this needs no `Date.parse`. */
function latestTimestamp(timestamps: readonly string[]): string | null {
  if (timestamps.length === 0) {
    return null;
  }
  let latest = timestamps[0] as string;
  for (const ts of timestamps) {
    if (ts > latest) latest = ts;
  }
  return latest;
}

export function summarizeChart(model: ChartModel): ChartSummary {
  const allTimestamps = model.series.flatMap((chartSeries) => chartSeries.points.map((point) => point.ts));
  const rangeTo = latestTimestamp(allTimestamps);

  return {
    metricName: model.metric.name,
    unit: model.metric.unit,
    rangeFrom: rangeTo === null ? null : model.coverage.servedFrom,
    rangeTo,
    seriesCount: model.series.length,
    series: model.series.map(summarizeSeries),
  };
}
