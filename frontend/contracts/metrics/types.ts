/**
 * Shared `contracts/metrics` vocabulary (task 071, flagged as this
 * consolidation point by `docs/plans/frontend/reports/task-070-report.md`
 * note 1). `contracts/` imports nothing app-side — no React, no DOM, no
 * `@shared/*` (task 046's acceptance box, enforced by `eslint.config.js`)
 * — because a future Java implementation reads this directory too
 * (`DM-D20`, `UI-D15`).
 *
 * This does not fold in `buildQuery.ts`'s or `metricsRepository.ts`'s own
 * `QueryRequest`/`QueryResponse` copies retroactively — each predates this
 * file for the same "contracts imports nothing" reason `buildQuery.ts`
 * documents, and de-duplicating them is a separate, deliberate change, not
 * a side effect of this task.
 */

// ---------------------------------------------------------------------------
// The range-query response (`dynamic-metrics` §5.6) — `projectSeries`'s input.
// ---------------------------------------------------------------------------

export type MetricType = 'counter' | 'gauge' | 'histogram';

export type RenderTransform = 'none' | 'rate' | 'delta';

/** `ok` (visited by no rule below) is the common case; the other four are declared, not inferred. */
export type SeriesState = 'ok' | 'inactive-replica' | 'pending-config-change' | 'type-suspect' | 'unverified-durability';

/**
 * `[ts, value, sampleCount, expectedCount]`. A bucket with no samples emits
 * **no tuple** — never a `null` or a `0` (`UI-D8`, `DM-D16`, obligation 1).
 */
export type Point = readonly [ts: string, value: number, sampleCount: number, expectedCount: number];

export interface SeriesRef {
  instanceId: string;
  placementId: string | null;
  sourceKey: string | null;
}

/**
 * A `sum`-across-replicas step that is not a workload change (`DM-D4` §4.4)
 * — server-declared, same reasoning as `gaps` below: the client renders the
 * event, it does not infer one from a value discontinuity.
 */
export interface ReplicaCountChange {
  ts: string;
  delta: number;
}

export interface QueryResponseSeries {
  seriesRef: SeriesRef;
  label: string;
  replicaComposition: { total: number; inactive: number };
  state: SeriesState;
  points: readonly Point[];
  /** Server-declared (`DM-D16`) — the client never re-derives `gapThresholdSeconds` itself (obligation 2). */
  gaps: readonly { from: string; to: string; reason: string }[];
  /** Present only when non-empty (`contracts/fixtures/README.md`'s absent-vs-empty convention). */
  replicaCountChanges?: readonly ReplicaCountChange[];
}

export interface QueryResponse {
  queryVersion: number;
  metric: { name: string; metricType: MetricType | null; unit: string | null };
  /** Server-decided from `metricType` (`SM-D31`) — never a raw monotonic counter climb (obligation 4). */
  render: { transform: RenderTransform; rateSeconds?: number };
  resolution: { requested: 'auto' | 'raw'; served: string; bucketSeconds: number };
  coverage: { requestedFrom: string; servedFrom: string; truncatedByRetention: boolean };
  gapThresholdSeconds: number;
  series: readonly QueryResponseSeries[];
  partial: { degraded: boolean; unavailable: readonly { instanceId: string; label: string; reason: string }[] };
}

// ---------------------------------------------------------------------------
// The chart theme and widget-config slices `projectSeries` consults.
// ---------------------------------------------------------------------------

/**
 * A pure, task-scoped mirror of `src/shared/theme/echartsTheme.ts`'s
 * `color: string[]` — `contracts/` cannot import that module (it pulls in
 * `@mui/material`'s `Theme`), so the caller adapts it into this shape at
 * the call site.
 */
export interface ChartTheme {
  /** The categorical palette, in assignment order — sequential, never hashed from an id (`DM-D13` §12). */
  categoricalColors: readonly string[];
}

/**
 * Only the slice of the `DM-D6` widget config schema `projectSeries`
 * itself consults. The full schema is task 084's — this mirrors
 * `buildQuery.ts`'s `WidgetQueryConfig`: a task-scoped slice, not a
 * premature copy of a schema that does not exist yet.
 */
export interface ChartWidgetConfig {
  series?: {
    colorStrategy?: 'palette';
    /** `instanceId -> explicit colour` — honoured "when it does not cause a collision" (§12). */
    colors?: Readonly<Record<string, string>>;
  };
}

// ---------------------------------------------------------------------------
// `mark` (`DM-D12`, `§7`) — THE ONLY visualization axis, a closed set.
// Declared here rather than local to a component for the same reason as
// `ChartThreshold` below: task 076 (gauge/single-stat rendering, `DM-D22`)
// and the eventual Java-side widget-schema implementation both need the
// same closed vocabulary. `MarkOptions` is styling only — it never changes
// what is queried (`DM-D12`'s own wording) — and is not part of
// `ChartWidgetConfig` for the same "task 071 scopes that to what
// `projectSeries` itself consults" reason `ChartThreshold` already gives.
// ---------------------------------------------------------------------------

/** Closed set (`SM-D31`, `DM-D12`). `area` is `MarkOptions.fill` on `line`, never a fourth member. */
export type Mark = 'line' | 'gauge' | 'single-stat';

export interface MarkOptions {
  /** "area" is this flag on `mark: 'line'`, not a vocabulary member (`DM-D12`). */
  fill?: boolean;
  /** Deferred (`SM-D31`) — present so the schema does not migrate when it is admitted. */
  stack?: boolean;
  scale?: 'linear' | 'log';
}

// ---------------------------------------------------------------------------
// Thresholds (`§7`'s `thresholds` schema slice) — a `ChartRenderer` (task
// 073) input, not `projectSeries`'s: a threshold changes nothing about the
// projected data, only how it is drawn, so it stays out of `ChartWidgetConfig`
// (which is scoped to what `projectSeries` itself consults, `task-071-report`
// decision 2). Still declared here, not local to the component, because §9.4
// names threshold rendering as one of the two reasons a Java implementation
// exists at all ("server-side rendering of thresholds and alert seeds").
// ---------------------------------------------------------------------------

export type ThresholdSeverity = 'warning' | 'critical';

export interface ChartThreshold {
  value: number;
  label: string;
  severity: ThresholdSeverity;
  /** Defaults to `'dashed'` — a threshold is never colour-only (§12, §18). */
  style?: 'dashed' | 'dotted';
}

// ---------------------------------------------------------------------------
// `ChartModel` — `projectSeries`'s output. Data in, data out; no ECharts
// option shape here, that is `ChartRenderer`'s (task 072) to build.
// ---------------------------------------------------------------------------

export interface ChartPoint {
  ts: string;
  value: number;
  /** `sampleCount < expectedCount` — a distinct marker, required at the raw tier too (obligation 5). */
  lowCoverage: boolean;
}

export interface ChartGap {
  from: string;
  to: string;
  reason: string;
}

export interface ChartCoverage {
  requestedFrom: string;
  servedFrom: string;
  truncatedByRetention: boolean;
  /** Present only when `truncatedByRetention` — the boundary is `servedFrom`; this is the band before it (obligation 6). */
  truncatedBand?: { from: string; to: string };
}

export type DashPattern = 'solid' | 'dashed';

export interface ChartSeries {
  seriesRef: SeriesRef;
  label: string;
  /** Carried through to tooltip and legend (task 074) — obligation 7. */
  state: SeriesState;
  replicaComposition: { total: number; inactive: number };
  color: string;
  /** `'dashed'` past palette size — the second visual channel, not a reused colour alone (obligation 8). */
  dashPattern: DashPattern;
  /** Never zero-filled (obligation 1); an all-gap or wholly-inactive series may be empty and still appears here for the legend. */
  points: readonly ChartPoint[];
  gaps: readonly ChartGap[];
  /** Carried through unchanged from `QueryResponseSeries`; present only when non-empty. */
  replicaCountChanges?: readonly ReplicaCountChange[];
}

export interface ChartModel {
  metric: { name: string; metricType: MetricType | null; unit: string | null };
  render: { transform: RenderTransform; rateSeconds?: number };
  coverage: ChartCoverage;
  series: readonly ChartSeries[];
}
