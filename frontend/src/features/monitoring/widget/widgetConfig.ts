/**
 * The widget configuration schema (task 084, Dec `DM-D6` `DM-D12` `DM-D21`,
 * `dynamic-metrics` §7) — exactly the fields §7 lists: `schemaVersion`,
 * `id`, `title`, `mark`, `options`, `query`, `range`, `resolution`, `axes`,
 * `legend`, `series`, `thresholds`, `refresh`. Fully serializable, which is
 * what makes dashboard persistence, Phase 2 URL sharing (`DM-D19`) and the
 * §16 alerting seed (`DM-D15`) fall out of one artifact rather than three.
 *
 * **`layer`/`scope` are deliberately absent.** §7's own field list has no
 * such entry, and for good reason: a shared widget, a Phase-2 shared URL,
 * and a `DM-D15` alert seed all carry this SAME document, and none of them
 * has a "layer" — that is dashboard-PLACEMENT metadata, which is why it
 * lives on `dashboardRepository.ts`'s `WidgetConfig` (the STORED,
 * dashboard-attached wrapper) rather than here.
 *
 * **`range` and `resolution` are defaults, not bindings** (`DM-D21`) — a
 * dashboard overrides them always. They still travel with the config
 * because a widget must be self-contained for URL serialisation and the
 * alert seed.
 *
 * **No styling editor exists yet.** `options`, `axes`, `legend`, `series`
 * and `thresholds` all have real, typed shapes below (so persistence and
 * migration are genuinely exercised), but `buildWidgetConfig` can only
 * fill them with the one default value nothing on screen lets an operator
 * change — same "real code, no live trigger yet" posture `AggregationControl`
 * (task 080) and the missing mark toggle already set elsewhere in this
 * feature (`ExplorePage.tsx`'s own header). Flagged per field below, not
 * silently decided.
 */
import type { ResolutionInput, WidgetQueryConfig } from '@contracts/metrics/buildQuery';
import type { ChartThreshold, Mark, MarkOptions } from '@contracts/metrics/types';

import type { TimeRangeValue } from '../explore/timeRangeOptions';

/** The console's own schema version — `migrations.ts` migrates any older document up to this. */
export const CURRENT_WIDGET_SCHEMA_VERSION = 1;

export interface WidgetAxisConfig {
  label: string;
  unitFormat: 'auto';
  min: number | null;
  max: number | null;
}

export interface WidgetAxesConfig {
  y: WidgetAxisConfig;
}

export interface WidgetLegendConfig {
  visible: boolean;
  /** §7's one example value — no legend-position control exists to offer another. */
  position: 'bottom';
  maxHeightRows: number;
}

export interface WidgetSeriesConfig {
  colorStrategy: 'palette';
  /** `instanceId -> explicit colour`, honoured "when it does not cause a collision" (§12). Empty until a colour picker exists. */
  colors: Readonly<Record<string, string>>;
}

export interface WidgetRefreshConfig {
  /** `null` means off — same shape `dashboardRepository.ts`'s `DashboardDoc.refresh` already uses. */
  intervalSeconds: number | null;
}

export interface WidgetConfigV1 {
  schemaVersion: 1;
  id: string;
  title: string;
  mark: Mark;
  options: MarkOptions;
  query: WidgetQueryConfig;
  /** DEFAULT only — a dashboard overrides it always (`DM-D21`). */
  range: TimeRangeValue;
  /** DEFAULT only — a dashboard overrides it always (`DM-D21`). */
  resolution: ResolutionInput;
  axes: WidgetAxesConfig;
  legend: WidgetLegendConfig;
  series: WidgetSeriesConfig;
  thresholds: readonly ChartThreshold[];
  refresh: WidgetRefreshConfig;
}

/** `w_` + 12 hex chars — short and opaque, same shape `exploreDraftStore.ts`'s own id generator uses, for the same "no shared id-minting module exists" reason. */
export function generateWidgetId(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return `w_${crypto.randomUUID().replace(/-/g, '').slice(0, 12)}`;
  }
  let id = '';
  for (let i = 0; i < 12; i += 1) {
    id += Math.floor(Math.random() * 16).toString(16);
  }
  return `w_${id}`;
}

export interface BuildWidgetConfigInput {
  title: string;
  mark: Mark;
  query: WidgetQueryConfig;
  range: TimeRangeValue;
  resolution: ResolutionInput;
}

/**
 * Assembles a fresh `v1` document from an Explore draft (§11.1 step 1-2)
 * plus an operator-entered title (step 4's `SaveWidgetDialog`). `id` is
 * injectable for tests; production callers take the generated default.
 *
 * `options`/`axes`/`legend`/`series`/`thresholds` are the ONE default value
 * below because no styling control exists anywhere in this app yet (§11.1
 * step 3, "Customize mark, axes, legend, colors, thresholds, scale", has
 * no `FE-*` task) — `legend.maxHeightRows: 4` is reused from `ChartRenderer
 * .tsx`'s own `DEFAULT_LEGEND_MAX_HEIGHT_ROWS`, §7's one concrete example,
 * rather than a second guess.
 */
export function buildWidgetConfig(
  input: BuildWidgetConfigInput,
  id: string = generateWidgetId(),
): WidgetConfigV1 {
  return {
    schemaVersion: CURRENT_WIDGET_SCHEMA_VERSION,
    id,
    title: input.title,
    mark: input.mark,
    options: {},
    query: input.query,
    range: input.range,
    resolution: input.resolution,
    axes: { y: { label: '', unitFormat: 'auto', min: null, max: null } },
    legend: { visible: true, position: 'bottom', maxHeightRows: 4 },
    series: { colorStrategy: 'palette', colors: {} },
    thresholds: [],
    refresh: { intervalSeconds: 60 },
  };
}
