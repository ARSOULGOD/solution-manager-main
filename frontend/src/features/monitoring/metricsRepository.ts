/**
 * `MetricsRepository` (task 033, §6.0.3, §6.0.5). SPECIFIED to endpoint
 * level (`dynamic-metrics` §5.4–5.8, §9.2's table) — implemented against
 * the real shape, unlike the other eleven seams. Routes go through
 * `consoleClient` (task 027) per that same table.
 */
import type { PlaneClient } from '@shared/api';
import type { Paged } from '@shared/api/contracts/common';
import type { CatalogEntry, MetricType } from '@shared/testdoubles/data/metrics';

export type AcrossInstances = 'none' | 'sum' | 'avg' | 'min' | 'max';
export type SeriesBy = 'instance' | 'replica';

export interface QueryRequest {
  queryVersion: 1;
  /** EXACTLY ONE metric per request (`DM-D23`). */
  metric: string;
  selector: {
    entityType: string;
    instanceIds: readonly string[];
    labels: Readonly<Record<string, string>>;
  };
  /** Absolute ISO-8601 UTC, resolved at issue time. */
  range: { from: string; to: string };
  /** No free bucket size (`SM-D31`). */
  resolution: 'auto' | 'raw';
  seriesBy: SeriesBy;
  aggregation: { acrossReplicas: 'auto' | AcrossInstances; acrossInstances: AcrossInstances };
  /** Composes AFTER instance aggregation only. */
  groupBy?: readonly string[];
}

/** [timestamp, value, sampleCount, expectedCount]. A bucket with no samples emits NO tuple (G10). */
export type Point = readonly [ts: string, value: number, sampleCount: number, expectedCount: number];

export type SeriesState =
  | 'ok'
  | 'inactive-replica'
  | 'pending-config-change'
  | 'type-suspect'
  | 'unverified-durability';

export interface QueryResponse {
  queryVersion: number;
  metric: { name: string; metricType: MetricType | null; unit: string | null };
  /** SERVER-decided. */
  render: { transform: 'none' | 'rate' | 'delta'; rateSeconds?: number };
  resolution: { requested: 'auto' | 'raw'; served: string; bucketSeconds: number };
  coverage: { requestedFrom: string; servedFrom: string; truncatedByRetention: boolean };
  /** SERVER-declared — the client never re-derives it. */
  gapThresholdSeconds: number;
  series: readonly {
    seriesRef: { instanceId: string; placementId: string | null; sourceKey: string | null };
    label: string;
    replicaComposition: { total: number; inactive: number };
    state: SeriesState;
    points: readonly Point[];
    gaps: readonly { from: string; to: string; reason: string }[];
  }[];
  // CONTRACT: TBD — item shape and reason vocabulary are INVENTED (§16 Q9).
  partial: { degraded: boolean; unavailable: readonly { instanceId: string; label: string; reason: string }[] };
}

export interface CapabilityTier {
  name: string;
  /** ISO-8601 duration, or `null` for unlimited (e.g. the `daily` tier). */
  retention: string | null;
  minBucketSeconds: number;
}

/**
 * The full `GET /capabilities` shape (task 068, `DM-D10`, `dynamic-metrics`
 * §5.4). Presets, caps and tiers are **derived** from this — nothing
 * downstream may hardcode one (enforced starting at `capabilityDefaults.ts`).
 */
export type CollectorReason =
  | 'scrape_failed'
  | 'parse_failed'
  | 'cap_rejected'
  | 'undeclared_dropped'
  | 'decrease_without_restart'
  | 'runway_degraded'
  | 'deadline_missed'
  | 'buffer_dropped'
  | 'out_of_order'
  | 'ambiguous_placement';

export interface Capabilities {
  queryVersion: number;
  tiers: readonly CapabilityTier[];
  /** ISO-8601 duration — the longest range any tier can serve. */
  maxRange: string;
  /** RENDERED series, not instances (`DM-D7`). */
  maxSeriesPerWidget: number;
  maxPointsPerSeries: number;
  marks: readonly ('line' | 'gauge' | 'single-stat')[];
  aggregations: readonly ('sum' | 'avg' | 'min' | 'max')[];
  features: { forceRaw: boolean; streamingExport: boolean; pngExport: boolean };
  /**
   * `console.yaml`'s `Capabilities.collector` (required, `OQ-16` (a), `I5`).
   * Install-level health (`state`, `runwayEnd`, `unfrozenClosedPartitions`,
   * `disk.state`) plus `reasons` counted over the CALLER's organization
   * only — never a byte figure (`sm_metrics`/database size are
   * install-wide and never printed by this API).
   */
  collector: {
    state: 'live' | 'degraded' | 'unknown';
    /** ISO-8601 date (UTC). Absent before the data plane's first partition keep. */
    runwayEnd?: string;
    unfrozenClosedPartitions: number;
    reasons: readonly { reason: CollectorReason; last24h: number }[];
    /** Absent when a disk budget is set but not yet measured. */
    disk?: { state: 'ok' | 'warning' | 'critical' | 'unconfigured' };
  };
}

export interface EntityType {
  id: string;
  label: string;
}

export interface MetricInstance {
  id: string;
  label: string;
  /**
   * CONTRACT: TBD — §5.5 specifies only the route, not a response shape,
   * so this field has no spec source. It exists because task 078's series
   * cap projection is `Σ replicas` under `seriesBy: 'replica'` (`DM-D7`,
   * §11.4) and cannot be computed client-side without a per-instance
   * replica count somewhere in this catalog entry.
   */
  replicaCount: number;
}

export interface InstanceQuery {
  search?: string;
}

export interface CatalogQuery {
  entityType?: string;
}

export interface LabelQuery {
  entityType?: string;
}

export interface MetricsRepository {
  capabilities(signal?: AbortSignal): Promise<Capabilities>;
  entityTypes(signal?: AbortSignal): Promise<EntityType[]>;
  instances(entityType: string, query: InstanceQuery, signal?: AbortSignal): Promise<Paged<MetricInstance>>;
  metricCatalog(query: CatalogQuery, signal?: AbortSignal): Promise<CatalogEntry[]>;
  labelValues(labelKey: string, query: LabelQuery, signal?: AbortSignal): Promise<string[]>;
  queryRange(req: QueryRequest, signal?: AbortSignal): Promise<QueryResponse>;
  queryInstant(req: QueryRequest, signal?: AbortSignal): Promise<QueryResponse>;
  /** Streamed via same-origin navigation (task 035) — never buffered, never a fetch(). */
  exportRangeUrl(req: QueryRequest): string;
  /**
   * `declareMetricType` is a **contracted refusal in v1** (`console.yaml`
   * row 61, M-Q6, `OR-75`): quarantine is deferred, so the real backend
   * answers `501 NOT_IMPLEMENTED` with `reason: 'metric-declare-deferred'`
   * for EVERY caller who holds the verb — there is no success response to
   * type. The promise always rejects with an `ApiError` carrying that
   * `code`/`reason`; callers must not branch on a resolved value.
   */
  declareMetricType(name: string, type: MetricType, unit: string): Promise<never>;
}

function opts(signal?: AbortSignal): { signal?: AbortSignal } {
  return signal !== undefined ? { signal } : {};
}

export function createMetricsRepository(consoleClient: PlaneClient): MetricsRepository {
  return {
    capabilities(signal) {
      return consoleClient.get<Capabilities>('/capabilities', opts(signal));
    },
    entityTypes(signal) {
      return consoleClient.get<EntityType[]>('/entity-types', opts(signal));
    },
    instances(entityType, query, signal) {
      const params = new URLSearchParams();
      if (query.search !== undefined && query.search.length > 0) params.set('search', query.search);
      const qs = params.toString();
      return consoleClient.get<Paged<MetricInstance>>(
        `/entity-types/${encodeURIComponent(entityType)}/instances${qs.length > 0 ? `?${qs}` : ''}`,
        opts(signal),
      );
    },
    metricCatalog(query, signal) {
      const params = new URLSearchParams();
      if (query.entityType !== undefined) params.set('entityType', query.entityType);
      const qs = params.toString();
      return consoleClient.get<CatalogEntry[]>(
        `/metrics/catalog${qs.length > 0 ? `?${qs}` : ''}`,
        opts(signal),
      );
    },
    labelValues(labelKey, query, signal) {
      const params = new URLSearchParams();
      if (query.entityType !== undefined) params.set('entityType', query.entityType);
      const qs = params.toString();
      return consoleClient.get<string[]>(
        `/labels/${encodeURIComponent(labelKey)}/values${qs.length > 0 ? `?${qs}` : ''}`,
        opts(signal),
      );
    },
    queryRange(req, signal) {
      return consoleClient.post<QueryResponse>('/query/range', req, opts(signal));
    },
    queryInstant(req, signal) {
      return consoleClient.post<QueryResponse>('/query/instant', req, opts(signal));
    },
    exportRangeUrl(req) {
      // A GET, same-origin, navigation-style download (task 035) — the
      // request body becomes a query string since a download link cannot
      // carry a POST body. `console.yaml`'s `exportRange` (row 60) takes
      // `metric`/`from`/`to` plus a REPEATED `instanceId` (style: form,
      // explode: true) — absent selects every instance the caller can
      // reach, so an empty selector omits the param rather than sending it
      // empty.
      const params = new URLSearchParams({ metric: req.metric, from: req.range.from, to: req.range.to });
      for (const instanceId of req.selector.instanceIds) {
        params.append('instanceId', instanceId);
      }
      return consoleClient.resolve(`/export/range?${params.toString()}`);
    },
    declareMetricType(name, type, unit) {
      // Always a contracted 501 in v1 (see the interface doc above) — this
      // call exists so the mutation still goes through the one request
      // path (correlation id, error codec) rather than the UI fabricating
      // the refusal itself.
      return consoleClient.post<never>(`/metrics/catalog/${encodeURIComponent(name)}/type`, {
        metricType: type,
        unit,
      });
    },
  };
}
