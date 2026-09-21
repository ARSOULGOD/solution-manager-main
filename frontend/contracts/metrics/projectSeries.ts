/**
 * `projectSeries()` — the chart data lifecycle's pure projection step (task
 * 071, `DM-D20`, `dynamic-metrics` §9.4, §5.6: "fetch → `projectSeries` →
 * render"). Data in, data out: no ECharts, no DOM, no `@shared/*`.
 * `DM-D20` requires this exist in TypeScript and, later, in Java, guarded
 * by the shared JSON fixture suite at `../fixtures/metrics/*.json`
 * (`contracts/fixtures/README.md` pins its number formatting and key
 * ordering, shared with task 046).
 *
 * Eight rules this function proves, each with its own fixture case
 * (`../fixtures/metrics/README-worthy list lives in the fixture directory
 * itself, one file per obligation):
 *
 * 1. A bucket with no samples produces no point — never `null`, never `0`.
 *    Structural: this function maps `QueryResponse.series[].points` 1:1,
 *    it never invents an entry.
 * 2. Gaps are server-declared (`gaps[]`); the threshold is never
 *    re-derived here — `QueryResponse.gapThresholdSeconds` is not even
 *    read by this module.
 * 3. No delta is computed across a gap — this function contains no
 *    subtraction between points at all; every value is the server's.
 * 4. Counters render as rate or delta from `render.transform`, passed
 *    through unchanged — this function never accumulates or integrates a
 *    series into a climbing plot.
 * 5. `sampleCount < expectedCount` marks a point low-coverage, uniformly —
 *    no resolution-tier branch, so raw is covered by construction.
 * 6. `coverage.truncatedByRetention` yields a boundary (`servedFrom`,
 *    already on `ChartCoverage`) and a band (`truncatedBand`, present only
 *    when truncated — "absent vs. present", not a null).
 * 7. Per-series `state` is carried through unchanged, and a series is
 *    never dropped for having zero points — an all-gap or wholly-inactive
 *    series still needs a legend entry (§12).
 * 8. Colours are assigned sequentially by array position, never hashed
 *    from an instance id; an explicit `widgetConfig.series.colors`
 *    override is honoured unless it collides with a colour already
 *    assigned in this chart; past palette size the colour cycles and
 *    `dashPattern` becomes the disambiguating channel.
 */
import type {
  ChartCoverage,
  ChartGap,
  ChartModel,
  ChartPoint,
  ChartSeries,
  ChartTheme,
  ChartWidgetConfig,
  DashPattern,
  Point,
  QueryResponse,
  QueryResponseSeries,
  ReplicaCountChange,
} from './types';

function projectPoint([ts, value, sampleCount, expectedCount]: Point): ChartPoint {
  return { ts, value, lowCoverage: sampleCount < expectedCount };
}

function projectGaps(gaps: QueryResponseSeries['gaps']): readonly ChartGap[] {
  return gaps.map((gap) => ({ from: gap.from, to: gap.to, reason: gap.reason }));
}

/** Present only when non-empty — mirrors `projectCoverage`'s `truncatedBand` (obligation 6's "absent vs. present"). */
function projectReplicaCountChanges(
  changes: QueryResponseSeries['replicaCountChanges'],
): readonly ReplicaCountChange[] | undefined {
  if (changes === undefined || changes.length === 0) {
    return undefined;
  }
  return changes.map((change) => ({ ts: change.ts, delta: change.delta }));
}

/**
 * Field-by-field, never a spread or a bare object reference — `§5.3`
 * requires unknown response fields be ignored, not carried through
 * (obligation "schema-migration" in the fixture suite). Applies to every
 * nested object below for the same reason, not only this one.
 */
function projectSeriesRef(seriesRef: QueryResponseSeries['seriesRef']): QueryResponseSeries['seriesRef'] {
  return { instanceId: seriesRef.instanceId, placementId: seriesRef.placementId, sourceKey: seriesRef.sourceKey };
}

function projectReplicaComposition(replicaComposition: QueryResponseSeries['replicaComposition']): QueryResponseSeries['replicaComposition'] {
  return { total: replicaComposition.total, inactive: replicaComposition.inactive };
}

function projectCoverage(coverage: QueryResponse['coverage']): ChartCoverage {
  const { requestedFrom, servedFrom, truncatedByRetention } = coverage;
  return truncatedByRetention
    ? { requestedFrom, servedFrom, truncatedByRetention, truncatedBand: { from: requestedFrom, to: servedFrom } }
    : { requestedFrom, servedFrom, truncatedByRetention };
}

interface SeriesStyle {
  color: string;
  dashPattern: DashPattern;
}

function styleKey(color: string, dashPattern: DashPattern): string {
  return `${color}|${dashPattern}`;
}

function paletteColorAt(palette: readonly string[], index: number): string {
  const color = palette[index % palette.length];
  if (color === undefined) {
    throw new Error('projectSeries: theme.categoricalColors must not be empty');
  }
  return color;
}

/**
 * A stateful assigner, one per `projectSeries` call, consulted once per
 * series in array order (obligation 8: sequential by position, never
 * hashed from an instance id). The palette index only advances on a
 * sequential (non-override) assignment, so an explicit override never
 * shifts where the next sequential series lands. Collisions are checked
 * on the **(colour, dashPattern) pair**, not colour alone — past palette
 * size the colour is meant to repeat, with dash pattern as the
 * disambiguating second channel (§12), so only an identical *pair* counts
 * as the collision "never a reused colour alone" warns against. Bounded by
 * `maxAttempts` so a pathological fixture (far more series than the
 * palette has solid/dashed slots for) cannot spin forever; the design does
 * not define behaviour past that point, and the real backstop is `DM-D7`'s
 * series cap, enforced before a response ever reaches this function.
 */
function createSeriesStyleAssigner(widgetConfig: ChartWidgetConfig, theme: ChartTheme): (instanceId: string) => SeriesStyle {
  const palette = theme.categoricalColors;
  const overrides = widgetConfig.series?.colors ?? {};
  const usedKeys = new Set<string>();
  const maxAttempts = palette.length * 4 + 4;
  let paletteIndex = 0;

  function nextSequential(): SeriesStyle {
    let color = paletteColorAt(palette, paletteIndex);
    let dashPattern: DashPattern = paletteIndex < palette.length ? 'solid' : 'dashed';
    let attempts = 0;
    while (usedKeys.has(styleKey(color, dashPattern)) && attempts < maxAttempts) {
      paletteIndex += 1;
      color = paletteColorAt(palette, paletteIndex);
      dashPattern = paletteIndex < palette.length ? 'solid' : 'dashed';
      attempts += 1;
    }
    paletteIndex += 1;
    return { color, dashPattern };
  }

  return (instanceId: string): SeriesStyle => {
    const override = overrides[instanceId];
    const style = override !== undefined && !usedKeys.has(styleKey(override, 'solid')) ? { color: override, dashPattern: 'solid' as const } : nextSequential();
    usedKeys.add(styleKey(style.color, style.dashPattern));
    return style;
  };
}

export function projectSeries(queryResponse: QueryResponse, widgetConfig: ChartWidgetConfig, theme: ChartTheme): ChartModel {
  const nextStyle = createSeriesStyleAssigner(widgetConfig, theme);

  const series: ChartSeries[] = queryResponse.series.map((s) => {
    const style = nextStyle(s.seriesRef.instanceId);
    const replicaCountChanges = projectReplicaCountChanges(s.replicaCountChanges);
    return {
      seriesRef: projectSeriesRef(s.seriesRef),
      label: s.label,
      state: s.state,
      replicaComposition: projectReplicaComposition(s.replicaComposition),
      color: style.color,
      dashPattern: style.dashPattern,
      points: s.points.map(projectPoint),
      gaps: projectGaps(s.gaps),
      ...(replicaCountChanges !== undefined ? { replicaCountChanges } : {}),
    };
  });

  return {
    metric: { name: queryResponse.metric.name, metricType: queryResponse.metric.metricType, unit: queryResponse.metric.unit },
    render:
      queryResponse.render.rateSeconds !== undefined
        ? { transform: queryResponse.render.transform, rateSeconds: queryResponse.render.rateSeconds }
        : { transform: queryResponse.render.transform },
    coverage: projectCoverage(queryResponse.coverage),
    series,
  };
}
