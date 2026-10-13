/**
 * `buildQuery()` — the query lifecycle's pure entry point (task 070,
 * `DM-D1` `DM-D2` `DM-D23`, `dynamic-metrics` §5.6, §9.1, §10: "filter
 * change → `buildQuery` → canonical hash → cache lookup → in-flight dedup →
 * fetch → `projectSeries` → render"). Data in, data out: no HTTP, no
 * `@shared/*`, no React — `contracts/` imports nothing app-side (task 046
 * acceptance, enforced by `eslint.config.js`), because a future Java
 * implementation reads this directory too.
 *
 * `QueryRequest` here is this task's own copy of the wire shape
 * `dynamic-metrics` §5.6 specifies — `src/features/monitoring/
 * metricsRepository.ts` (task 033) predates this task and keeps its own
 * copy for the same reason. Task 071's `types.ts` is where a shared
 * `contracts/metrics` vocabulary gets consolidated; this file does not
 * pre-empt that.
 *
 * **Relative ranges resolve to absolute at issue time, not at render
 * time** (§10) — that is why `now` is a parameter (defaulted to the real
 * clock) rather than read inline: a fixed clock lets a test assert the
 * exact resolved window instead of a moving target, and the same draft
 * config issued a minute later legitimately asks a different absolute
 * window.
 */

export type SeriesBy = 'instance' | 'replica';
export type AcrossInstances = 'none' | 'sum' | 'avg' | 'min' | 'max';

export interface WidgetQuerySelector {
  entityType: string;
  instanceIds: readonly string[];
  /** Omitted means no label filter, not an empty-but-present filter. */
  labels?: Readonly<Record<string, string>>;
}

/**
 * The operator-facing draft state `<FilterBar>`'s controls hold — one
 * metric (`DM-D23`, enforced here by `metric` being a single `string`,
 * never a list), the instance selection, and both aggregation axes in
 * their fixed evaluation order (§5.7). Range and resolution are a
 * separate concern (`QueryTimeRange`) because `<TimeRangePicker>` and
 * `<ResolutionControl>` are shared across `<ExploreView>` and
 * `<DashboardToolbar>` rather than owned by the filter bar.
 */
export interface WidgetQueryConfig {
  metric: string;
  selector: WidgetQuerySelector;
  seriesBy: SeriesBy;
  aggregation: { acrossReplicas: 'auto' | AcrossInstances; acrossInstances: AcrossInstances };
  /** Composes AFTER instance aggregation only (§5.7) — the caller gates this, buildQuery does not re-derive it. */
  groupBy?: readonly string[];
}

export type TimeRangePreset = '15m' | '1h' | '6h' | '24h' | '7d' | '30d' | '90d' | '6mo';

export type TimeRangeInput =
  | { kind: 'relative'; preset: TimeRangePreset }
  | { kind: 'absolute'; from: string; to: string };

export type ResolutionInput = 'auto' | 'raw';

export interface QueryTimeRange {
  range: TimeRangeInput;
  resolution: ResolutionInput;
}

/**
 * Only the fields `buildQuery` itself needs to consult. Not the full
 * `Capabilities` shape (task 068) — that would require importing
 * app-side code, which `contracts/` may not do.
 */
export interface QueryCapabilities {
  features: { forceRaw: boolean };
}

export interface QueryRequest {
  queryVersion: 1;
  /** EXACTLY ONE metric per request (`DM-D23`) — a list is a malformed `400`, not a business refusal. */
  metric: string;
  selector: {
    entityType: string;
    instanceIds: readonly string[];
    labels: Readonly<Record<string, string>>;
  };
  /** Absolute ISO-8601 UTC, resolved at issue time — never a relative token on the wire. */
  range: { from: string; to: string };
  /** No free bucket size (`SM-D31`). */
  resolution: ResolutionInput;
  seriesBy: SeriesBy;
  aggregation: { acrossReplicas: 'auto' | AcrossInstances; acrossInstances: AcrossInstances };
  groupBy?: readonly string[];
}

const RELATIVE_PRESET_MS: Readonly<Record<Exclude<TimeRangePreset, '6mo'>, number>> = {
  '15m': 15 * 60_000,
  '1h': 60 * 60_000,
  '6h': 6 * 60 * 60_000,
  '24h': 24 * 60 * 60_000,
  '7d': 7 * 24 * 60 * 60_000,
  '30d': 30 * 24 * 60 * 60_000,
  '90d': 90 * 24 * 60 * 60_000,
};

/** Calendar-month subtraction, not a fixed day count — a month is not always 30 days. */
function subtractCalendarMonths(date: Date, months: number): Date {
  const result = new Date(date.getTime());
  result.setUTCMonth(result.getUTCMonth() - months);
  return result;
}

function resolveRange(input: TimeRangeInput, now: Date): { from: string; to: string } {
  if (input.kind === 'absolute') {
    return { from: input.from, to: input.to };
  }
  const to = now;
  const from = input.preset === '6mo' ? subtractCalendarMonths(to, 6) : new Date(to.getTime() - RELATIVE_PRESET_MS[input.preset]);
  return { from: from.toISOString(), to: to.toISOString() };
}

/**
 * Fails closed to `auto` when `raw` is requested but the deployment has
 * not advertised `features.forceRaw` (task 068, `DM-D10`) — the same
 * fail-closed posture as `capabilityDefaults.ts`. `<ResolutionControl>`
 * (task 081) is expected to gate the choice before it ever reaches here;
 * this is the one shared place that enforces it regardless, so the rule
 * lives once rather than being re-implemented in both `<ExploreView>` and
 * `<DashboardToolbar>`.
 */
function resolveResolution(requested: ResolutionInput, capabilities: QueryCapabilities): ResolutionInput {
  return requested === 'raw' && !capabilities.features.forceRaw ? 'auto' : requested;
}

export function buildQuery(
  widgetConfig: WidgetQueryConfig,
  timeRange: QueryTimeRange,
  capabilities: QueryCapabilities,
  now: () => Date = () => new Date(),
): QueryRequest {
  const request: QueryRequest = {
    queryVersion: 1,
    metric: widgetConfig.metric,
    selector: {
      entityType: widgetConfig.selector.entityType,
      instanceIds: widgetConfig.selector.instanceIds,
      labels: widgetConfig.selector.labels ?? {},
    },
    range: resolveRange(timeRange.range, now()),
    resolution: resolveResolution(timeRange.resolution, capabilities),
    seriesBy: widgetConfig.seriesBy,
    aggregation: widgetConfig.aggregation,
  };
  return widgetConfig.groupBy !== undefined ? { ...request, groupBy: widgetConfig.groupBy } : request;
}
